import { sql } from 'drizzle-orm';
import type { PoolTx } from '@/db/pool';

/**
 * Serializa las transacciones de sync que escriben para un mismo usuario. Debe ser la PRIMERA
 * sentencia de toda transacción que asigne `sync_changes.sequence` (push v1 y push legacy): sin
 * ella, dos transacciones concurrentes pueden confirmar secuencias fuera de orden y el cursor de
 * pull saltaría para siempre la secuencia menor confirmada tarde. Se libera al terminar la tx.
 */
export async function lockUserSync(tx: PoolTx, userId: string): Promise<void> {
  await tx.execute(sql`SELECT pg_advisory_xact_lock(hashtextextended('gymlog-sync:' || ${userId}::text, 0))`);
}
