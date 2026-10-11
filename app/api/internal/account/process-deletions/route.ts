import { randomUUID } from 'node:crypto';
import { isAuthorizedCronRequest } from '@/lib/api/internal-auth';
import { errorResponse, jsonResponse } from '@/lib/api/v1/response';
import { getAccountDeletionService } from '@/lib/account/runtime';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';
export const maxDuration = 60;

/** Trabajos reanudados por invocación (acota la duración de la función). */
const RESUME_LIMIT = 10;

// Reanuda borrados de cuenta que quedaron a medias (fallo de R2/Postgres/Clerk o función cortada).
// Ruta interna sin sesión de Clerk: la llave es `Authorization: Bearer $CRON_SECRET`. El cron de
// Vercel (vercel.json) llama con GET; POST queda para disparos manuales. Idempotente.
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
    const result = await getAccountDeletionService().resumePending({ limit: RESUME_LIMIT });
    return jsonResponse(result, requestId);
  } catch (error) {
    return errorResponse(error, requestId);
  }
}

export const GET = handle;
export const POST = handle;
