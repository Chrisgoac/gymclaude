import { sql } from 'drizzle-orm';
import type { PoolTx } from '@/db/pool';
import { accountDeleting, isAccountDeleting } from '@/lib/account/access';

/**
 * Toma el advisory lock de sync del usuario SIN comprobar nada más. Solo para el borrado de cuenta,
 * que necesita serializarse con los escritores pero es justo quien ha bloqueado la cuenta.
 */
export async function acquireUserSyncLock(tx: Pick<PoolTx, 'execute'>, userId: string): Promise<void> {
  await tx.execute(sql`SELECT pg_advisory_xact_lock(hashtextextended('gymlog-sync:' || ${userId}::text, 0))`);
}

/**
 * Serializa las transacciones de sync que escriben para un mismo usuario. Debe ser la PRIMERA
 * sentencia de toda transacción que asigne `sync_changes.sequence` (push v1 y push legacy): sin
 * ella, dos transacciones concurrentes pueden confirmar secuencias fuera de orden y el cursor de
 * pull saltaría para siempre la secuencia menor confirmada tarde. Se libera al terminar la tx.
 *
 * Con el lock ya tomado comprueba además que la cuenta no esté en borrado (403 ACCOUNT_DELETING):
 * una escritura que pasó el guard de la ruta justo antes de pedirse el borrado y esperaba este lock
 * no puede volver a insertar filas después del paso de Postgres del borrado. La comprobación es una
 * sentencia aparte para que su snapshot (READ COMMITTED) sea posterior a la espera del lock.
 * Requiere la migración 0005 (account_deletion_jobs).
 */
export async function lockUserSync(tx: PoolTx, userId: string): Promise<void> {
  await acquireUserSyncLock(tx, userId);
  if (await isAccountDeleting(tx, userId)) throw accountDeleting();
}
