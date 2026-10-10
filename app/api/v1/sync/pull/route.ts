import { getPoolDb } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import { enforceRateLimit } from '@/lib/api/v1/rate-limit';
import { withApiRoute } from '@/lib/api/v1/route';
import { pullChanges } from '@/lib/api/v1/sync/pull';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

/** Tamaño de página por defecto y máximo: un `limit` mayor se recorta, no se rechaza. */
export const MAX_PULL_LIMIT = 500;

function parseLimit(raw: string | null): number {
  if (raw === null) return MAX_PULL_LIMIT;
  const limit = /^[0-9]+$/.test(raw) ? Number(raw) : NaN;
  if (!Number.isSafeInteger(limit) || limit < 1) {
    throw new ApiError(400, 'INVALID_REQUEST', 'Invalid request', false, {
      limit: ['Must be an integer greater than or equal to 1'],
    });
  }
  return Math.min(limit, MAX_PULL_LIMIT);
}

export const GET = withApiRoute(async (request, principal) => {
  await enforceRateLimit('sync', principal.userId);
  const params = new URL(request.url).searchParams;
  const limit = parseLimit(params.get('limit'));

  // Solo lectura: el diario y las filas se leen en la misma transacción (sin lock: no escribe).
  const page = await getPoolDb().transaction((tx) => pullChanges(tx, principal, params.get('cursor'), limit), {
    accessMode: 'read only',
  });
  return Response.json(page, { headers: { 'cache-control': 'no-store' } });
});
