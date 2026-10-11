import { randomUUID } from 'node:crypto';
import { isAuthorizedCronRequest } from '@/lib/api/internal-auth';
import { errorResponse, jsonResponse } from '@/lib/api/v1/response';
import { getMediaService } from '@/lib/api/v1/media/runtime';
import type { ProcessDeletionsResult } from '@/lib/api/v1/media/service';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

/** Objetos reclamados por lote y lotes máximos por invocación (acota la duración de la función). */
const DELETION_BATCH_LIMIT = 50;
const DELETION_MAX_BATCHES = 10;

// Borrado diferido de objetos de medios (`media_deletions`). Ruta interna sin sesión de Clerk: la
// llave es `Authorization: Bearer $CRON_SECRET`. El cron de Vercel (vercel.json) llama con GET;
// POST queda para disparos manuales. Idempotente: repetirla no borra nada dos veces.
async function handle(request: Request): Promise<Response> {
  const requestId = randomUUID();
  if (!isAuthorizedCronRequest(request)) {
    return jsonResponse(
      { error: { code: 'AUTH_REQUIRED', message: 'Authentication required', retryable: false } },
      requestId,
      401,
    );
  }
  try {
    const service = getMediaService();
    const total: ProcessDeletionsResult & { batches: number } = { deleted: 0, cancelled: 0, failed: 0, batches: 0 };
    while (total.batches < DELETION_MAX_BATCHES) {
      const batch = await service.processDeletions({ limit: DELETION_BATCH_LIMIT });
      total.batches++;
      total.deleted += batch.deleted;
      total.cancelled += batch.cancelled;
      total.failed += batch.failed;
      if (batch.deleted + batch.cancelled + batch.failed < DELETION_BATCH_LIMIT) break;
    }
    return jsonResponse(total, requestId);
  } catch (error) {
    return errorResponse(error, requestId);
  }
}

export const GET = handle;
export const POST = handle;
