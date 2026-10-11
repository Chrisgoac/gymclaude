import { createHash } from 'node:crypto';
import { sql } from 'drizzle-orm';
import { getPoolDb, type PoolDb, type PoolTx } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';

// Bloqueo de acceso de una cuenta en borrado: basta con que exista su fila en
// `account_deletion_jobs` (la crea el primer paso del borrado y nunca se borra). Módulo pequeño y
// sin dependencias del orquestador para que requirePrincipal, lockUserSync y las rutas legacy lo
// importen sin arrastrar R2 ni Clerk.

/** Identificador no reversible de la cuenta en `account_deletion_jobs` y en los logs del borrado. */
export function accountHash(userId: string): string {
  return createHash('sha256').update(`gymlog-account:${userId}`).digest('hex');
}

export const accountDeleting = () =>
  new ApiError(403, 'ACCOUNT_DELETING', 'This account is being deleted', false);

/** SQLSTATE 42P01 (tabla inexistente): sin la migración 0005 no puede haber ningún borrado en curso. */
function isUndefinedTable(error: unknown): boolean {
  for (let e: unknown = error, depth = 0; e instanceof Error && depth < 5; e = e.cause, depth++) {
    if ((e as { code?: unknown }).code === '42P01') return true;
  }
  return false;
}

/** ¿Hay un borrado pedido (pendiente o terminado) para esta cuenta? Lectura por clave primaria. */
export async function isAccountDeleting(q: Pick<PoolDb | PoolTx, 'execute'>, userId: string): Promise<boolean> {
  try {
    const result = await q.execute(
      sql`SELECT 1 AS deleting FROM account_deletion_jobs WHERE user_id_hash = ${accountHash(userId)} LIMIT 1`,
    );
    return (result?.rows?.length ?? 0) > 0;
  } catch (error) {
    if (isUndefinedTable(error)) return false;
    throw error;
  }
}

/** Lanza 403 ACCOUNT_DELETING si la cuenta tiene un borrado pedido. */
export async function assertAccountActive(userId: string, q: Pick<PoolDb | PoolTx, 'execute'> = getPoolDb()): Promise<void> {
  if (await isAccountDeleting(q, userId)) throw accountDeleting();
}

/**
 * Guard de las rutas legacy de la PWA (texto plano, como su 401): 403 si la cuenta está en
 * borrado, o null para seguir.
 */
export async function legacyDeletingResponse(userId: string): Promise<Response | null> {
  return (await isAccountDeleting(getPoolDb(), userId)) ? new Response('ACCOUNT_DELETING', { status: 403 }) : null;
}
