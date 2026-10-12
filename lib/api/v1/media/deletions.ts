import { createHash } from 'node:crypto';
import { mediaDeletions } from '@/db/schema';
import type { PoolDb, PoolTx } from '@/db/pool';
import { isAccountDeleting } from '@/lib/account/access';
import { MEDIA_KINDS, type MediaKind } from './contracts';

// Claves de objeto y cola de borrado diferido (`media_deletions`). Módulo sin dependencias de
// la sync para que el push v1/legacy y las rutas legacy de la PWA puedan encolar borrados en su
// propia transacción.

/** Espera entre el tombstone y el borrado del objeto: margen para que otros dispositivos converjan. */
export const MEDIA_DELETION_DELAY_MS = 24 * 60 * 60_000;

/** Prefijo de toda clave v1 (bucket privado). El resto son claves legacy de la PWA (bucket público). */
export const V1_MEDIA_KEY_PREFIX = 'users/';

/** Tablas sincronizadas que guardan una clave de objeto en `key`. */
export const MEDIA_SYNC_TABLES = { exercisePhotos: 'exercise', progressPhotos: 'progress' } as const satisfies Record<
  string,
  MediaKind
>;

/** Hash estable y no reversible del userId: las claves no exponen el id de Clerk. */
export function userHash(userId: string): string {
  return createHash('sha256').update(`gymlog-media:${userId}`).digest('hex').slice(0, 32);
}

export function mediaObjectKey(userId: string, kind: MediaKind, uuid: string): string {
  return `${V1_MEDIA_KEY_PREFIX}${userHash(userId)}/${kind}/${uuid}`;
}

const isTraversal = (key: string) => key.includes('..') || key.includes('//');

/** Clave v1 del usuario (namespace `users/<hash>/<kind>/`); con `kind`, solo de ese tipo. */
export function isOwnedV1Key(userId: string, key: string, kind?: MediaKind): boolean {
  if (isTraversal(key)) return false;
  const kinds = kind ? [kind] : MEDIA_KINDS;
  return kinds.some((k) => key.startsWith(`${V1_MEDIA_KEY_PREFIX}${userHash(userId)}/${k}/`));
}

/**
 * Una clave es del usuario si está en su namespace v1 (del tipo indicado) o en su namespace legacy
 * de la PWA (`<userId>/…`). Cualquier otra (p. ej. una clave ajena colada por la sync) nunca se
 * firma ni se borra.
 */
export function isOwnedMediaKey(userId: string, key: string, kind?: MediaKind): boolean {
  if (isTraversal(key)) return false;
  return isOwnedV1Key(userId, key, kind) || key.startsWith(`${userId}/`);
}

/**
 * Encola el borrado diferido de un objeto. Idempotente: si ya estaba en cola no se reprograma.
 * `processDeletions` lo cancela si la fila del dueño vuelve a estar viva con esa clave.
 */
export async function enqueueMediaDeletion(
  q: PoolDb | PoolTx,
  input: { userId: string; objectKey: string; at: number },
): Promise<void> {
  // An account being deleted gets no new queue rows: its objects go with the account deletion.
  if (await isAccountDeleting(q, input.userId)) return;
  await q
    .insert(mediaDeletions)
    .values({
      objectKey: input.objectKey,
      userId: input.userId,
      dueAt: input.at + MEDIA_DELETION_DELAY_MS,
      createdAt: input.at,
    })
    .onConflictDoNothing();
}

/**
 * Tras una escritura sincronizada de una foto (push v1 o legacy): si la fila quedó tombstoneada o
 * cambió de clave, encola el borrado de la clave v1 propia que deja de usarse. Las claves legacy
 * las borra la propia PWA con su DELETE; las ajenas nunca se encolan.
 */
export async function enqueueReleasedMediaKey(
  tx: PoolTx,
  input: {
    table: string;
    userId: string;
    before: { key: unknown; deletedAt: number | null } | null;
    after: { key?: unknown; deletedAt: unknown };
    at: number;
  },
): Promise<void> {
  const kind = (MEDIA_SYNC_TABLES as Record<string, MediaKind | undefined>)[input.table];
  if (!kind) return;
  const { before, after, userId, at } = input;
  const released = new Set<string>();
  const afterKey = typeof after.key === 'string' ? after.key : null;
  const tombstoned = after.deletedAt !== null && after.deletedAt !== undefined;
  if (tombstoned && afterKey) released.add(afterKey);
  if (before && typeof before.key === 'string' && (tombstoned || before.key !== afterKey)) released.add(before.key);
  for (const key of released) {
    if (isOwnedV1Key(userId, key, kind)) await enqueueMediaDeletion(tx, { userId, objectKey: key, at });
  }
}
