import { createHash, randomUUID } from 'node:crypto';
import { and, eq, isNull, sql } from 'drizzle-orm';
import { exercisePhotos, exercises, mediaDeletions, progressPhotos, syncChanges } from '@/db/schema';
import type { PoolDb, PoolTx } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import { lockUserSync } from '@/lib/api/v1/sync/lock';
import { SYNC_TABLE_ADAPTERS, type RowWrite, type SyncTableAdapter } from '@/lib/api/v1/sync/tables';
import {
  ALLOWED_MEDIA_TYPES,
  ExerciseMediaFieldsSchema,
  MEDIA_KINDS,
  MEDIA_LIMITS,
  ProgressMediaFieldsSchema,
  type AllowedMediaType,
  type ExerciseMediaFields,
  type MediaDeleteResponse,
  type MediaKind,
  type MediaReadGrant,
  type MediaUploadResponse,
  type ProgressMediaFields,
} from './contracts';

// Servicio de medios privados v1: valida bytes, genera la clave en el servidor, escribe la fila
// por el diario de sync (sync_changes/serverVersion) y firma lecturas breves. El borrado tombstonea
// la fila y encola el borrado diferido e idempotente del objeto (`processDeletions`).

/** Puerto de almacenamiento (R2 en producción: `r2MediaStorage`; doble en tests). */
export interface MediaStorage {
  putPrivate(key: string, bytes: Uint8Array, contentType: string): Promise<void>;
  signRead(key: string, ttlSeconds: number): Promise<string>;
  remove(key: string): Promise<void>;
}

export interface MediaServiceDeps {
  db: PoolDb;
  storage: MediaStorage;
  now?: () => number;
  uuid?: () => string;
}

export type MediaUploadInput =
  | { kind: 'exercise'; fields: ExerciseMediaFields; bytes: Uint8Array; declaredMime: string }
  | { kind: 'progress'; fields: ProgressMediaFields; bytes: Uint8Array; declaredMime: string };

export interface ProcessDeletionsResult {
  deleted: number;
  cancelled: number;
  failed: number;
}

/** Espera entre el tombstone y el borrado del objeto: margen para que otros dispositivos converjan. */
export const MEDIA_DELETION_DELAY_MS = 24 * 60 * 60_000;
/** Lease de un borrado reclamado: otro proceso no lo toca mientras dure. */
const DELETION_LEASE_MS = 5 * 60_000;
const DELETION_RETRY_BASE_MS = 15 * 60_000;
const DELETION_RETRY_MAX_MS = 24 * 60 * 60_000;

/** `lastDeviceId` de toda escritura que hace el servicio de medios. */
export const MEDIA_SERVICE_DEVICE_ID = 'server-media';

const TABLES = {
  exercise: { name: 'exercisePhotos', table: exercisePhotos },
  progress: { name: 'progressPhotos', table: progressPhotos },
} as const;

const notFound = () => new ApiError(404, 'MEDIA_NOT_FOUND', 'Media not found', false);
const invalid = (code: string, message: string, status = 400) => new ApiError(status, code, message, false);

// ── Claves ──

/** Hash estable y no reversible del userId: las claves no exponen el id de Clerk. */
export function userHash(userId: string): string {
  return createHash('sha256').update(`gymlog-media:${userId}`).digest('hex').slice(0, 32);
}

export function mediaObjectKey(userId: string, kind: MediaKind, uuid: string): string {
  return `users/${userHash(userId)}/${kind}/${uuid}`;
}

/** Ruta autenticada de la app que redirige a una URL firmada (`url` de las filas v1). */
export function mediaReadRoute(mediaId: string): string {
  return `/api/media/read/${mediaId}`;
}

/**
 * Una clave es del usuario si está en su namespace v1 del tipo correcto o en su namespace legacy
 * de la PWA (`<userId>/…`). Cualquier otra (p. ej. una clave ajena colada por la sync) nunca se
 * firma ni se borra.
 */
function isOwnedKey(userId: string, kind: MediaKind, key: string): boolean {
  if (key.includes('..') || key.includes('//')) return false;
  return key.startsWith(`users/${userHash(userId)}/${kind}/`) || key.startsWith(`${userId}/`);
}

function isOwnedByUser(userId: string, key: string): boolean {
  return MEDIA_KINDS.some((kind) => isOwnedKey(userId, kind, key));
}

// ── Validación de bytes ──

export interface ImageInfo {
  mime: AllowedMediaType;
  width: number;
  height: number;
}

const u16be = (b: Uint8Array, i: number) => (b[i] << 8) | b[i + 1];
const u32be = (b: Uint8Array, i: number) => ((b[i] << 24) >>> 0) + ((b[i + 1] << 16) | (b[i + 2] << 8) | b[i + 3]);
const u16le = (b: Uint8Array, i: number) => b[i] | (b[i + 1] << 8);
const u24le = (b: Uint8Array, i: number) => b[i] | (b[i + 1] << 8) | (b[i + 2] << 16);
const tagAt = (b: Uint8Array, i: number, tag: string) =>
  b.length >= i + tag.length && [...tag].every((c, j) => b[i + j] === c.charCodeAt(0));

function inspectJpeg(b: Uint8Array): ImageInfo | null {
  let i = 2;
  while (i + 3 < b.length) {
    if (b[i] !== 0xff) return null;
    const marker = b[i + 1];
    if (marker === 0xff) {
      i += 1; // relleno
      continue;
    }
    // Marcadores sin longitud.
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd8)) {
      i += 2;
      continue;
    }
    if (marker === 0xd9 || marker === 0xda) return null; // fin o datos de escaneo antes del SOF
    const length = u16be(b, i + 2);
    if (length < 2) return null;
    const isSof = marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc;
    if (isSof) {
      if (i + 9 > b.length || length < 7) return null;
      return { mime: 'image/jpeg', height: u16be(b, i + 5), width: u16be(b, i + 7) };
    }
    i += 2 + length;
  }
  return null;
}

function inspectWebp(b: Uint8Array): ImageInfo | null {
  if (tagAt(b, 12, 'VP8X') && b.length >= 30) {
    return { mime: 'image/webp', width: 1 + u24le(b, 24), height: 1 + u24le(b, 27) };
  }
  if (tagAt(b, 12, 'VP8L') && b.length >= 25 && b[20] === 0x2f) {
    const width = 1 + (b[21] | ((b[22] & 0x3f) << 8));
    const height = 1 + ((b[22] >> 6) | (b[23] << 2) | ((b[24] & 0x0f) << 10));
    return { mime: 'image/webp', width, height };
  }
  if (tagAt(b, 12, 'VP8 ') && b.length >= 30 && b[23] === 0x9d && b[24] === 0x01 && b[25] === 0x2a) {
    return { mime: 'image/webp', width: u16le(b, 26) & 0x3fff, height: u16le(b, 28) & 0x3fff };
  }
  return null;
}

/** Tipo y dimensiones según los magic bytes y la cabecera; null si no es una imagen admitida. */
export function inspectImage(b: Uint8Array): ImageInfo | null {
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return inspectJpeg(b);
  const pngSig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (b.length >= 8 && pngSig.every((v, i) => b[i] === v)) {
    if (b.length < 24 || !tagAt(b, 12, 'IHDR')) return null;
    return { mime: 'image/png', width: u32be(b, 16), height: u32be(b, 20) };
  }
  if (tagAt(b, 0, 'RIFF') && tagAt(b, 8, 'WEBP')) return inspectWebp(b);
  return null;
}

function normalizeMime(declared: string): string {
  const base = declared.split(';')[0].trim().toLowerCase();
  return base === 'image/jpg' ? 'image/jpeg' : base;
}

/** Valida tamaño, tipo declarado vs. magic bytes y dimensiones decodificadas. Sin E/S. */
export function validateMediaBytes(bytes: Uint8Array, declaredMime: string): ImageInfo & { bytes: number } {
  if (bytes.byteLength > MEDIA_LIMITS.maxBytes) {
    throw invalid('MEDIA_TOO_LARGE', `Media exceeds ${MEDIA_LIMITS.maxBytes} bytes`, 413);
  }
  const declared = normalizeMime(declaredMime);
  if (!(ALLOWED_MEDIA_TYPES as readonly string[]).includes(declared)) {
    throw invalid('UNSUPPORTED_MEDIA_TYPE', 'Media type is not supported', 415);
  }
  const info = bytes.byteLength === 0 ? null : inspectImage(bytes);
  if (!info) throw invalid('INVALID_MEDIA', 'Media content is not a supported image');
  if (info.mime !== declared) throw invalid('MEDIA_TYPE_MISMATCH', 'Media content does not match its declared type');
  const { width, height } = info;
  if (
    width < 1 || height < 1 ||
    width > MEDIA_LIMITS.maxDimension || height > MEDIA_LIMITS.maxDimension ||
    width * height > MEDIA_LIMITS.maxPixels
  ) {
    throw invalid('MEDIA_DIMENSIONS_INVALID', 'Media dimensions are out of bounds');
  }
  return { ...info, bytes: bytes.byteLength };
}

/** 400 si el ejercicio existe y es de otro usuario (ids desconocidos se toleran, como en la sync). */
async function assertOwnedExercise(q: PoolDb | PoolTx, userId: string, exerciseId: string): Promise<void> {
  const found = await q.select({ userId: exercises.userId }).from(exercises).where(eq(exercises.id, exerciseId));
  if (found.some((r) => r.userId !== userId)) {
    throw new ApiError(400, 'INVALID_REFERENCE', 'Record references an unavailable record', false);
  }
}

function parseFields(input: MediaUploadInput): ExerciseMediaFields | ProgressMediaFields {
  const schema = input.kind === 'exercise' ? ExerciseMediaFieldsSchema : ProgressMediaFieldsSchema;
  const parsed = schema.safeParse(input.fields);
  if (!parsed.success) {
    const details: Record<string, string[]> = {};
    for (const issue of parsed.error.issues) {
      const path = issue.path.join('.') || '_';
      (details[path] ??= []).push(issue.message);
    }
    throw new ApiError(400, 'VALIDATION_FAILED', 'Invalid media fields', false, details);
  }
  return parsed.data;
}

// ── Servicio ──

type MediaRow = { id: string; userId: string; key: string; deletedAt: number | null };

export function createMediaService(deps: MediaServiceDeps) {
  const { db, storage } = deps;
  const now = deps.now ?? Date.now;
  const uuid = deps.uuid ?? randomUUID;
  const adapterFor = (kind: MediaKind) =>
    SYNC_TABLE_ADAPTERS[TABLES[kind].name] as unknown as SyncTableAdapter<'exercisePhotos' | 'progressPhotos'>;

  async function grantFor(mediaId: string, key: string): Promise<MediaReadGrant> {
    const issuedAt = now();
    let readUrl: string;
    try {
      readUrl = await storage.signRead(key, MEDIA_LIMITS.readUrlTtlSeconds);
    } catch {
      throw new ApiError(503, 'MEDIA_STORAGE_UNAVAILABLE', 'Media storage unavailable', true);
    }
    return { mediaId, readUrl, expiresAt: issuedAt + MEDIA_LIMITS.readUrlTtlSeconds * 1000 };
  }

  async function findRow(kind: MediaKind, mediaId: string): Promise<MediaRow | undefined> {
    const t = TABLES[kind].table;
    const [row] = await db
      .select({ id: t.id, userId: t.userId, key: t.key, deletedAt: t.deletedAt })
      .from(t)
      .where(eq(t.id, mediaId));
    return row;
  }

  /** Respuesta de una subida ya hecha con ese id (reintento) o error si el id no está disponible. */
  async function replayExisting(userId: string, kind: MediaKind, row: MediaRow): Promise<MediaUploadResponse> {
    if (row.userId !== userId) throw new ApiError(409, 'RECORD_ID_CONFLICT', 'Record id is not available', false);
    if (row.deletedAt !== null) throw new ApiError(409, 'MEDIA_DELETED', 'Media was deleted', false);
    if (!isOwnedKey(userId, kind, row.key)) {
      throw new ApiError(409, 'RECORD_ID_CONFLICT', 'Record id is not available', false);
    }
    const grant = await grantFor(row.id, row.key);
    return { mediaId: row.id, objectKey: row.key, readUrl: grant.readUrl, readUrlExpiresAt: grant.expiresAt };
  }

  async function upload(userId: string, input: MediaUploadInput): Promise<MediaUploadResponse> {
    const fields = parseFields(input);
    const info = validateMediaBytes(input.bytes, input.declaredMime);
    const { kind } = input;
    const adapter = adapterFor(kind);
    const mediaId = fields.id ?? uuid();

    // Antes de subir: referencias ajenas y reintentos se resuelven sin tocar R2 (vía rápida; se
    // repite dentro de la transacción). El id debe estar libre en las DOS tablas de fotos, porque
    // readGrant/delete resuelven por id sin tipo.
    const assertExerciseReference = (q: PoolDb | PoolTx) =>
      kind === 'exercise' ? assertOwnedExercise(q, userId, (fields as ExerciseMediaFields).exerciseId) : Promise.resolve();
    await assertExerciseReference(db);
    const previous = await findRow(kind, mediaId);
    if (previous) return replayExisting(userId, kind, previous);
    const otherKind = MEDIA_KINDS.find((k) => k !== kind)!;
    if (await findRow(otherKind, mediaId)) {
      throw new ApiError(409, 'RECORD_ID_CONFLICT', 'Record id is not available', false);
    }

    const objectKey = mediaObjectKey(userId, kind, uuid());
    try {
      await storage.putPrivate(objectKey, input.bytes, info.mime);
    } catch {
      throw new ApiError(503, 'MEDIA_STORAGE_UNAVAILABLE', 'Media storage unavailable', true);
    }

    const removeOrphan = () => storage.remove(objectKey).catch(() => {});
    let inserted: boolean;
    try {
      inserted = await db.transaction(async (tx) => {
        await lockUserSync(tx, userId);
        await assertExerciseReference(tx);
        const otherTable = TABLES[otherKind].table;
        const [taken] = await tx.select({ id: otherTable.id }).from(otherTable).where(eq(otherTable.id, mediaId));
        if (taken) throw new ApiError(409, 'RECORD_ID_CONFLICT', 'Record id is not available', false);
        const at = now();
        const domain =
          kind === 'exercise'
            ? { exerciseId: (fields as ExerciseMediaFields).exerciseId }
            : {
                fecha: (fields as ProgressMediaFields).fecha,
                angulo: (fields as ProgressMediaFields).angulo,
                nota: (fields as ProgressMediaFields).nota ?? null,
              };
        const values = {
          id: mediaId,
          userId,
          updatedAt: at,
          deletedAt: null,
          serverUpdatedAt: at,
          lastDeviceId: MEDIA_SERVICE_DEVICE_ID,
          lastMutationId: uuid(),
          url: mediaReadRoute(mediaId),
          key: objectKey,
          mime: info.mime,
          bytes: info.bytes,
          width: info.width,
          height: info.height,
          ...domain,
        } as RowWrite;
        // Otra subida con el mismo id ganó la carrera: se resuelve fuera contra su fila.
        if (!(await adapter.insertIfAbsent(tx, values))) return false;
        const [change] = await tx
          .insert(syncChanges)
          .values({ userId, tableName: TABLES[kind].name, recordId: mediaId, changedAt: at })
          .returning({ sequence: syncChanges.sequence });
        await adapter.update(tx, userId, mediaId, { serverVersion: change.sequence });
        return true;
      });
    } catch (e) {
      await removeOrphan();
      throw e;
    }

    if (!inserted) {
      await removeOrphan();
      const winner = await findRow(kind, mediaId);
      if (!winner) throw new ApiError(409, 'WRITE_RACE', 'Record changed concurrently, retry', true);
      return replayExisting(userId, kind, winner);
    }
    const grant = await grantFor(mediaId, objectKey);
    return { mediaId, objectKey, readUrl: grant.readUrl, readUrlExpiresAt: grant.expiresAt };
  }

  async function readGrant(userId: string, mediaId: string): Promise<MediaReadGrant> {
    for (const kind of MEDIA_KINDS) {
      const t = TABLES[kind].table;
      const [row] = await db
        .select({ key: t.key })
        .from(t)
        .where(and(eq(t.id, mediaId), eq(t.userId, userId), isNull(t.deletedAt)));
      if (row && isOwnedKey(userId, kind, row.key)) return grantFor(mediaId, row.key);
    }
    // Ajeno, borrado o inexistente: misma respuesta, no se revela si existe.
    throw notFound();
  }

  async function remove(userId: string, mediaId: string): Promise<MediaDeleteResponse> {
    const found = await db.transaction(async (tx) => {
      await lockUserSync(tx, userId);
      for (const kind of MEDIA_KINDS) {
        const adapter = adapterFor(kind);
        let existing;
        try {
          existing = await adapter.lockOwnedRow(tx, userId, mediaId);
        } catch (e) {
          if (e instanceof ApiError && e.code === 'RECORD_ID_CONFLICT') continue; // ajena: como si no existiera
          throw e;
        }
        if (!existing) continue;

        const key = existing.row.key as string;
        const at = now();
        if (existing.deletedAt === null) {
          const values = {
            deletedAt: at,
            // LWW: el tombstone debe ganar aunque el reloj del cliente fuera por delante.
            updatedAt: Math.max(at, existing.updatedAt + 1),
            serverUpdatedAt: at,
            lastDeviceId: MEDIA_SERVICE_DEVICE_ID,
            lastMutationId: uuid(),
          };
          const [change] = await tx
            .insert(syncChanges)
            .values({ userId, tableName: TABLES[kind].name, recordId: mediaId, changedAt: at })
            .returning({ sequence: syncChanges.sequence });
          await adapter.update(tx, userId, mediaId, { ...values, serverVersion: change.sequence });
        }
        // Repetir el borrado es éxito; reprograma solo si el borrado no estaba ya en cola.
        if (isOwnedKey(userId, kind, key)) {
          await tx
            .insert(mediaDeletions)
            .values({ objectKey: key, userId, dueAt: at + MEDIA_DELETION_DELAY_MS, createdAt: at })
            .onConflictDoNothing();
        }
        return true;
      }
      return false;
    });
    if (!found) throw notFound();
    return { mediaId, deleted: true };
  }

  /**
   * Borra los objetos cuyo plazo venció. Idempotente y seguro con varias instancias: cada fila se
   * reclama con un lease (`due_at` adelantado, SKIP LOCKED) y el trabajo de red va fuera de la
   * transacción. Si la fila de la foto vuelve a estar viva, el borrado se cancela. Pensado para un
   * cron o ruta interna futura; no hay infraestructura de cron todavía.
   */
  async function processDeletions(options: { limit?: number } = {}): Promise<ProcessDeletionsResult> {
    const limit = options.limit ?? 25;
    const at = now();
    const claimed = await db.execute<{ object_key: string; user_id: string; attempts: number }>(sql`
      UPDATE media_deletions SET due_at = ${at + DELETION_LEASE_MS}
      WHERE object_key IN (
        SELECT object_key FROM media_deletions WHERE due_at <= ${at}
        ORDER BY due_at LIMIT ${limit} FOR UPDATE SKIP LOCKED
      )
      RETURNING object_key, user_id, attempts`);

    const result: ProcessDeletionsResult = { deleted: 0, cancelled: 0, failed: 0 };
    for (const { object_key: key, user_id: owner, attempts } of claimed.rows) {
      const done = () => db.delete(mediaDeletions).where(eq(mediaDeletions.objectKey, key));
      const live = await db.execute(sql`
        SELECT 1 FROM exercise_photos WHERE key = ${key} AND user_id = ${owner} AND deleted_at IS NULL
        UNION ALL
        SELECT 1 FROM progress_photos WHERE key = ${key} AND user_id = ${owner} AND deleted_at IS NULL
        LIMIT 1`);
      // En uso de nuevo por su dueño, o una clave fuera de su namespace: nunca se borra. Una fila
      // ajena que copie la clave (vía sync) no cancela nada: solo cuenta la del dueño.
      if (live.rows.length > 0 || !isOwnedByUser(owner, key)) {
        await done();
        result.cancelled++;
        continue;
      }
      try {
        await storage.remove(key);
        await done();
        result.deleted++;
      } catch (e) {
        const next = attempts + 1;
        const backoff = Math.min(DELETION_RETRY_BASE_MS * next, DELETION_RETRY_MAX_MS);
        await db
          .update(mediaDeletions)
          .set({ attempts: next, lastError: String(e instanceof Error ? e.message : e).slice(0, 500), dueAt: at + backoff })
          .where(eq(mediaDeletions.objectKey, key));
        result.failed++;
      }
    }
    return result;
  }

  return { upload, readGrant, delete: remove, processDeletions };
}

export type MediaService = ReturnType<typeof createMediaService>;
