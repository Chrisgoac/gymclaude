import { randomUUID } from 'node:crypto';
import { and, eq, inArray, isNull, lte, or, sql } from 'drizzle-orm';
import type { PgTable } from 'drizzle-orm/pg-core';
import { z } from 'zod';
import * as schema from '@/db/schema';
import type { PoolDb, PoolTx } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import { hashSubject } from '@/lib/api/v1/rate-limit';
import { isOwnedMediaKey, userHash, V1_MEDIA_KEY_PREFIX } from '@/lib/api/v1/media/deletions';
import { acquireUserSyncLock } from '@/lib/api/v1/sync/lock';
import { createDbEntitlementStore, toEntitlementResponse, type EntitlementStore } from '@/lib/billing/entitlements';
import { accountHash } from './access';

// Borrado completo y reanudable de una cuenta (DELETE /api/v1/account y /delete-account).
//
// Pasos, en orden y cada uno idempotente (repetirlo tras un fallo parcial es seguro):
//   0. bloqueo   — se inserta la fila de `account_deletion_jobs`: desde ese instante el guard de
//                  requirePrincipal/lockUserSync/rutas legacy niega el acceso (403 ACCOUNT_DELETING).
//   1. storage   — objetos R2 del usuario: prefijo privado `users/<hash>/`, prefijo legacy público
//                  `<userId>/` y las claves propias que aún citan sus filas de fotos / media_deletions.
//   2. database  — todas las filas del usuario en todas las tablas con ámbito de usuario, en UNA
//                  transacción serializada con los escritores de sync (mismo advisory lock).
//   3. storage_sweep — segunda pasada de R2 por prefijo: recoge una subida legacy que estuviera en
//                  vuelo cuando se bloqueó la cuenta.
//   4. identity  — borra el usuario de Clerk (404 = ya borrado = éxito).
//   5. database_sweep — repite el borrado de filas (y la pasada de R2) cuando han pasado al menos
//                  FINAL_SWEEP_DELAY_MS desde la petición: recoge lo que escribiera una petición que
//                  pasó el guard antes del bloqueo y escribe sin el lock de sync (informe de IA,
//                  verify de Play, RTDN, rate limit…; ya rechazan escribir si ven el bloqueo, esto
//                  cubre la carrera residual). Antes de ese plazo el trabajo sigue pendiente sin
//                  error y lo termina el cron o un DELETE repetido.
//   6. done      — `user_id` pasa a NULL: solo queda el hash, el estado y las marcas de tiempo.
// Un fallo deja `step` en el paso que falló y `last_error_code`; lo reanuda el siguiente DELETE o
// /api/internal/account/process-deletions. Los logs llevan solo hash corto, paso y código.

export const DELETION_CONFIRMATION = 'ELIMINAR';
/** Autenticación reciente exigida para PEDIR el borrado: primer factor verificado hace < 10 min. */
export const RECENT_AUTH_MAX_MINUTES = 10;
/** Lease de un ejecutor: más que la duración máxima de la función que lo ejecuta. */
export const DELETION_LEASE_MS = 5 * 60_000;
const DELETE_CONCURRENCY = 8;

export const DeleteAccountRequestSchema = z.object({ confirm: z.literal(DELETION_CONFIRMATION) }).strict();

export const DELETION_STEPS = ['storage', 'database', 'storage_sweep', 'identity', 'database_sweep'] as const;
/**
 * Espera mínima entre la petición y el barrido final: más que la duración máxima de cualquier
 * función que pudiera estar escribiendo para el usuario (maxDuration 60 s) con margen.
 */
export const FINAL_SWEEP_DELAY_MS = 120_000;
export type DeletionStep = (typeof DELETION_STEPS)[number] | 'done';

const STEP_ERROR_CODES: Record<(typeof DELETION_STEPS)[number], string> = {
  storage: 'STORAGE_FAILED',
  database: 'DATABASE_FAILED',
  storage_sweep: 'STORAGE_FAILED',
  identity: 'IDENTITY_FAILED',
  database_sweep: 'DATABASE_FAILED',
};

/** El barrido final aún no puede correr (no es un fallo: el trabajo sigue pendiente sin código). */
class SweepNotDue extends Error {}

/**
 * Clase (y estado HTTP / SQLSTATE si los hay) de un error, saneada: nunca el mensaje (puede llevar
 * ids o datos). Ej.: `ClerkAPIResponseError:429`, `DatabaseError:40P01`, `S3ServiceException:403`.
 */
export function errorClassOf(error: unknown): string {
  const raw = error instanceof Error ? error.constructor?.name || error.name : typeof error;
  const name = /^[A-Za-z0-9_]{1,64}$/.test(raw) ? raw : 'Error';
  const e = error as { status?: unknown; statusCode?: unknown; code?: unknown; $metadata?: { httpStatusCode?: unknown }; cause?: { code?: unknown } };
  const status = [e?.status, e?.statusCode, e?.$metadata?.httpStatusCode].find((v) => Number.isInteger(v) && (v as number) >= 100 && (v as number) < 600);
  if (status !== undefined) return `${name}:${status}`;
  const code = [e?.code, e?.cause?.code].find((v) => typeof v === 'string' && /^[0-9A-Z]{5}$/.test(v));
  return code ? `${name}:${code}` : name;
}

export const reauthRequired = () =>
  new ApiError(403, 'REAUTH_REQUIRED', 'Sign in again (within the last 10 minutes) to delete the account', false);

/**
 * ¿Verificó el primer factor hace ≤ RECENT_AUTH_MAX_MINUTES? `fva` es el claim de Clerk (minutos
 * desde la última verificación de primer y segundo factor; -1 = nunca). Sin claim → no reciente.
 */
export function isRecentAuth(fva: readonly [number, number] | null | undefined): boolean {
  if (!fva) return false;
  const [firstFactorAge] = fva;
  return Number.isFinite(firstFactorAge) && firstFactorAge >= 0 && firstFactorAge < RECENT_AUTH_MAX_MINUTES;
}

// ── Puertos ──

/** Almacenamiento R2 (producción: `r2AccountStorage`; dobles en tests). */
export interface AccountStorage {
  listPrivate(prefix: string): Promise<string[]>;
  listLegacy(prefix: string): Promise<string[]>;
  removePrivate(key: string): Promise<void>;
  removeLegacy(key: string): Promise<void>;
}

/** Proveedor de identidad (producción: Clerk). Debe ser idempotente: un usuario ya borrado es éxito. */
export interface IdentityProvider {
  deleteUser(userId: string): Promise<void>;
}

export interface AccountDeletionDeps {
  db: PoolDb;
  storage: AccountStorage;
  identity: IdentityProvider;
  entitlements?: Pick<EntitlementStore, 'findBest'>;
  now?: () => number;
  /** Ganchos de prueba: se llaman tras completar cada paso (inyectar un fallo/caída). */
  afterStep?: (step: DeletionStep) => Promise<void> | void;
  log?: (line: string) => void;
}

export interface DeletionJob {
  userIdHash: string;
  status: 'pending' | 'done';
  step: DeletionStep;
  attempts: number;
  lastErrorCode: string | null;
}

export interface DeletionOutcome {
  status: 'deleted' | 'in_progress';
  step: DeletionStep;
  /** Código estable del último fallo (se reanudará), o null. */
  errorCode: string | null;
}

export interface ActiveSubscription {
  productId: string;
  state: 'ACTIVE' | 'GRACE' | 'PAUSED' | 'CANCELED';
  /** epoch ms hasta el que Google mantiene el acceso pagado. */
  validUntil: number | null;
  /** true si Google seguirá cobrando: hay que cancelarla en Google Play aparte. */
  willRenew: boolean;
  manageUrl: string;
}

const DEFAULT_PACKAGE = 'com.chrisgoac.gymlog';

/** Enlace a la gestión de la suscripción en Google Play (el servidor nunca la cancela). */
export function playManageUrl(productId?: string | null): string {
  const pkg = process.env.GOOGLE_PLAY_PACKAGE_NAME?.trim() || DEFAULT_PACKAGE;
  const params = new URLSearchParams({ package: pkg });
  if (productId) params.set('sku', productId);
  return `https://play.google.com/store/account/subscriptions?${params.toString()}`;
}

/**
 * Suscripción de Google Play que seguirá viva tras borrar la cuenta (el servidor nunca la cancela):
 * ACTIVE/GRACE/PAUSED, o CANCELED aún dentro del periodo pagado. Las de prueba (`source='test'`) no.
 */
export async function activeSubscriptionFor(
  store: Pick<EntitlementStore, 'findBest'>,
  userId: string,
  now: number,
): Promise<ActiveSubscription | null> {
  const row = await store.findBest(userId, now);
  if (!row || row.source !== 'play') return null;
  const projected = toEntitlementResponse(row, now);
  const state = projected.state;
  if (state !== 'ACTIVE' && state !== 'GRACE' && state !== 'PAUSED' && state !== 'CANCELED') return null;
  if (state === 'CANCELED' && projected.tier !== 'PRO') return null;
  return {
    productId: row.productId,
    state,
    validUntil: projected.validUntil,
    willRenew: state !== 'CANCELED' && row.autoRenewing,
    manageUrl: playManageUrl(row.productId),
  };
}

// ── Ámbito exacto de datos ──

/** Las 14 tablas sincronizadas (todas con `user_id`). */
export const SYNCED_USER_TABLES = [
  schema.exercises,
  schema.routines,
  schema.routineExercises,
  schema.workoutSessions,
  schema.loggedExercises,
  schema.loggedSets,
  schema.gyms,
  schema.exercisePhotos,
  schema.userSettings,
  schema.coachMessages,
  schema.bodyMetrics,
  schema.progressPhotos,
  schema.mesocycles,
  schema.achievements,
] as const;

/** Tablas solo-servidor con columna `user_id`. */
export const SERVER_USER_TABLES = [
  schema.reportedAiResponses,
  schema.aiResponses,
  schema.aiReports,
  schema.mediaDeletions,
  schema.syncMutations,
  schema.syncChanges,
  schema.subscriptionEntitlements,
] as const;

/**
 * Tablas cuyo vínculo con el usuario no es una columna `user_id`; se borran por otra clave exacta:
 * `api_rate_buckets` por `subject_hash = hashSubject(userId)` (todas las políticas usan el userId
 * como sujeto) y `billing_events` por los hashes de token de las suscripciones del usuario.
 * `account_deletion_jobs` es el propio registro del borrado (sin contenido) y no se borra.
 */
export const INDIRECT_USER_TABLES = ['api_rate_buckets', 'billing_events', 'account_deletion_jobs'] as const;

function shortHash(userId: string): string {
  return accountHash(userId).slice(0, 12);
}

async function forEachLimited<T>(items: T[], limit: number, fn: (item: T) => Promise<void>): Promise<void> {
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) await fn(items[next++]);
  });
  await Promise.all(workers);
}

function rowToJob(r: Record<string, unknown>): DeletionJob {
  return {
    userIdHash: String(r.user_id_hash),
    status: r.status as DeletionJob['status'],
    step: r.step as DeletionStep,
    attempts: Number(r.attempts),
    lastErrorCode: (r.last_error_code as string | null) ?? null,
  };
}

export function createAccountDeletionService(deps: AccountDeletionDeps) {
  const { db, storage, identity } = deps;
  const now = deps.now ?? Date.now;
  const log = deps.log ?? ((line: string) => console.warn(line));
  const entitlements = deps.entitlements ?? createDbEntitlementStore(() => db);

  async function findJob(userId: string): Promise<DeletionJob | null> {
    const result = await db.execute(sql`
      SELECT user_id_hash, status, step, attempts, last_error_code
      FROM account_deletion_jobs WHERE user_id_hash = ${accountHash(userId)}`);
    const r = result.rows[0] as Record<string, unknown> | undefined;
    return r ? rowToJob(r) : null;
  }

  /** Paso 0 (bloqueo). Idempotente: si ya existe, no cambia nada. Devuelve si lo ha creado. */
  async function requestDeletion(userId: string): Promise<boolean> {
    const at = now();
    const inserted = await db
      .insert(schema.accountDeletionJobs)
      .values({
        userIdHash: accountHash(userId),
        userId,
        status: 'pending',
        step: DELETION_STEPS[0],
        requestedAt: at,
        updatedAt: at,
      })
      .onConflictDoNothing()
      .returning({ hash: schema.accountDeletionJobs.userIdHash });
    if (inserted.length) log(`[account-deletion] job=${shortHash(userId)} step=lock requested`);
    return inserted.length > 0;
  }

  /** Suscripción de Google Play que el usuario debe cancelar aparte, o null. Leer ANTES del paso database. */
  function readActiveSubscription(userId: string): Promise<ActiveSubscription | null> {
    return activeSubscriptionFor(entitlements, userId, now());
  }

  // ── Pasos ──

  async function storageKeysFromRows(userId: string): Promise<string[]> {
    const keys: string[] = [];
    for (const table of [schema.exercisePhotos, schema.progressPhotos]) {
      const rows = await db.select({ key: table.key }).from(table).where(eq(table.userId, userId));
      keys.push(...rows.map((r) => r.key));
    }
    const pending = await db
      .select({ key: schema.mediaDeletions.objectKey })
      .from(schema.mediaDeletions)
      .where(eq(schema.mediaDeletions.userId, userId));
    keys.push(...pending.map((r) => r.key));
    // Una clave ajena colada por la sync nunca se borra.
    return keys.filter((k) => isOwnedMediaKey(userId, k));
  }

  async function deleteStorage(userId: string, includeRowKeys: boolean): Promise<void> {
    if (!userId || userId.includes('/')) throw new Error('unsafe user id for an R2 prefix');
    const privatePrefix = `${V1_MEDIA_KEY_PREFIX}${userHash(userId)}/`;
    const legacyPrefix = `${userId}/`;
    const privateKeys = new Set(await storage.listPrivate(privatePrefix));
    const legacyKeys = new Set(await storage.listLegacy(legacyPrefix));
    if (includeRowKeys) {
      for (const key of await storageKeysFromRows(userId)) {
        if (key.startsWith(privatePrefix)) privateKeys.add(key);
        else if (key.startsWith(legacyPrefix)) legacyKeys.add(key);
      }
    }
    // Defensa en profundidad: solo se borra lo que está bajo los prefijos exactos del usuario.
    const priv = [...privateKeys].filter((k) => k.startsWith(privatePrefix));
    const legacy = [...legacyKeys].filter((k) => k.startsWith(legacyPrefix));
    await forEachLimited(priv, DELETE_CONCURRENCY, (k) => storage.removePrivate(k));
    await forEachLimited(legacy, DELETE_CONCURRENCY, (k) => storage.removeLegacy(k));
  }

  async function deleteRows(userId: string): Promise<void> {
    await db.transaction(async (tx: PoolTx) => {
      // Serializa con push v1/legacy y subidas de medios: ninguna escritura en vuelo confirma después.
      await acquireUserSyncLock(tx, userId);
      const ent = schema.subscriptionEntitlements;
      const tokenHashes = tx.select({ h: ent.purchaseTokenHash }).from(ent).where(eq(ent.userId, userId));
      await tx.delete(schema.billingEvents).where(inArray(schema.billingEvents.purchaseTokenHash, tokenHashes));
      await tx.delete(schema.apiRateBuckets).where(eq(schema.apiRateBuckets.subjectHash, hashSubject(userId)));
      for (const table of [...SERVER_USER_TABLES, ...SYNCED_USER_TABLES] as (PgTable & { userId: typeof schema.gyms.userId })[]) {
        await tx.delete(table).where(eq(table.userId, userId));
      }
    });
  }

  async function runStep(step: (typeof DELETION_STEPS)[number], userId: string, requestedAt: number): Promise<void> {
    switch (step) {
      case 'storage':
        return deleteStorage(userId, true);
      case 'database':
        return deleteRows(userId);
      case 'storage_sweep':
        return deleteStorage(userId, false);
      case 'identity':
        return identity.deleteUser(userId);
      case 'database_sweep':
        if (now() < requestedAt + FINAL_SWEEP_DELAY_MS) throw new SweepNotDue();
        await deleteRows(userId);
        return deleteStorage(userId, false);
    }
  }

  /**
   * Reclama el trabajo (lease) y ejecuta los pasos que faltan. Si otro ejecutor tiene el lease,
   * devuelve el estado sin tocar nada. Nunca lanza por un fallo de paso: lo registra y devuelve
   * `in_progress` con el código.
   */
  async function runJob(userId: string): Promise<DeletionOutcome> {
    const hash = accountHash(userId);
    const owner = randomUUID();
    const at = now();
    const jobs = schema.accountDeletionJobs;
    const claimed = await db
      .update(jobs)
      .set({ leaseOwner: owner, leaseExpiresAt: at + DELETION_LEASE_MS, attempts: sql`${jobs.attempts} + 1`, updatedAt: at })
      .where(and(
        eq(jobs.userIdHash, hash),
        eq(jobs.status, 'pending'),
        or(isNull(jobs.leaseExpiresAt), lte(jobs.leaseExpiresAt, at)),
      ))
      .returning({ step: jobs.step, userId: jobs.userId, requestedAt: jobs.requestedAt });
    if (!claimed.length) {
      const job = await findJob(userId);
      if (!job) throw new Error('account deletion job not found');
      return { status: job.status === 'done' ? 'deleted' : 'in_progress', step: job.step, errorCode: job.lastErrorCode };
    }
    if (claimed[0].userId !== userId) throw new Error('account deletion job does not match the user');

    const ours = and(eq(jobs.userIdHash, hash), eq(jobs.leaseOwner, owner));
    let step = claimed[0].step as DeletionStep;
    while (step !== 'done') {
      const current = step;
      try {
        await runStep(current, userId, claimed[0].requestedAt);
        await deps.afterStep?.(current);
      } catch (error) {
        if (error instanceof SweepNotDue) {
          await db.update(jobs).set({ leaseOwner: null, leaseExpiresAt: null, updatedAt: now() }).where(ours);
          return { status: 'in_progress', step: current, errorCode: null };
        }
        const code = STEP_ERROR_CODES[current];
        const errorClass = errorClassOf(error);
        log(`[account-deletion] job=${hash.slice(0, 12)} step=${current} error=${code} cause=${errorClass}`);
        await db
          .update(jobs)
          .set({ lastErrorCode: code, lastErrorClass: errorClass, leaseOwner: null, leaseExpiresAt: null, updatedAt: now() })
          .where(ours);
        return { status: 'in_progress', step: current, errorCode: code };
      }
      const index = DELETION_STEPS.indexOf(current as (typeof DELETION_STEPS)[number]);
      const next: DeletionStep = index + 1 < DELETION_STEPS.length ? DELETION_STEPS[index + 1] : 'done';
      step = next;
      const done = next === 'done';
      const advanced = await db
        .update(jobs)
        .set(done
          ? { step, status: 'done', userId: null, lastErrorCode: null, lastErrorClass: null, leaseOwner: null, leaseExpiresAt: null, updatedAt: now(), completedAt: now() }
          : { step, lastErrorCode: null, lastErrorClass: null, leaseExpiresAt: now() + DELETION_LEASE_MS, updatedAt: now() })
        .where(ours)
        .returning({ step: jobs.step });
      if (!advanced.length) {
        // Lease perdido (otro ejecutor lo reclamó tras caducar): ese sigue; los pasos son idempotentes.
        const job = await findJob(userId);
        return { status: job?.status === 'done' ? 'deleted' : 'in_progress', step: job?.step ?? step, errorCode: null };
      }
      log(`[account-deletion] job=${hash.slice(0, 12)} step=${current} ok`);
    }
    return { status: 'deleted', step: 'done', errorCode: null };
  }

  /** Cron: reanuda los trabajos pendientes con el lease libre (los más antiguos primero). */
  async function resumePending(options: { limit: number }): Promise<{ deleted: number; pending: number }> {
    const at = now();
    const jobs = schema.accountDeletionJobs;
    const rows = await db
      .select({ userId: jobs.userId })
      .from(jobs)
      .where(and(eq(jobs.status, 'pending'), or(isNull(jobs.leaseExpiresAt), lte(jobs.leaseExpiresAt, at))))
      .orderBy(jobs.updatedAt)
      .limit(options.limit);
    const result = { deleted: 0, pending: 0 };
    for (const { userId } of rows) {
      if (!userId) continue;
      const outcome = await runJob(userId);
      if (outcome.status === 'deleted') result.deleted++;
      else result.pending++;
    }
    return result;
  }

  return { findJob, requestDeletion, readActiveSubscription, runJob, resumePending };
}

export type AccountDeletionService = ReturnType<typeof createAccountDeletionService>;

// ── Caso de uso compartido por DELETE /api/v1/account y la página /delete-account ──

export interface DeleteAccountResult extends DeletionOutcome {
  /**
   * Cuenta borrada para el usuario (datos, objetos y Clerk) y solo falta el barrido final de
   * seguridad, que corre solo pasados FINAL_SWEEP_DELAY_MS (cron). `status` es entonces `deleted`.
   */
  finalCleanupPending: boolean;
  /** Suscripción de Google Play viva al pedir el borrado (el servidor NO la cancela), o null. */
  activeSubscription: ActiveSubscription | null;
}

/**
 * Crea o reanuda el borrado. Pedirlo por primera vez exige autenticación reciente; reanudar uno ya
 * pedido no (el usuario ya confirmó y la cuenta ya está bloqueada). La suscripción se lee antes del
 * paso database para que el cliente pueda avisar; en una reanudación posterior puede ser null.
 */
export async function deleteAccount(
  service: AccountDeletionService,
  input: {
    userId: string;
    factorVerificationAge: readonly [number, number] | null | undefined;
    /** Rate limit del llamante; no se gasta para contestar a un borrado ya terminado. */
    beforeWork?: () => Promise<void>;
  },
): Promise<DeleteAccountResult> {
  const existing = await service.findJob(input.userId);
  if (existing?.status === 'done') {
    return { status: 'deleted', step: 'done', errorCode: null, activeSubscription: null, finalCleanupPending: false };
  }
  await input.beforeWork?.();
  if (!existing && !isRecentAuth(input.factorVerificationAge)) throw reauthRequired();
  const activeSubscription = await service.readActiveSubscription(input.userId);
  if (!existing) await service.requestDeletion(input.userId);
  const outcome = await service.runJob(input.userId);
  if (outcome.status === 'in_progress' && outcome.step === 'database_sweep' && !outcome.errorCode) {
    return { status: 'deleted', step: 'database_sweep', errorCode: null, activeSubscription, finalCleanupPending: true };
  }
  return { ...outcome, activeSubscription, finalCleanupPending: false };
}
