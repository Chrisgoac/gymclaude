import { getPoolDb } from '@/db/pool';
import { PushRequestSchema } from '@/lib/api/v1/contracts/sync';
import { ApiError } from '@/lib/api/v1/errors';
import { enforceRateLimit } from '@/lib/api/v1/rate-limit';
import { withApiRoute } from '@/lib/api/v1/route';
import { MAX_PUSH_BODY_BYTES } from '@/lib/api/v1/sync/limits';
import { lockUserSync } from '@/lib/api/v1/sync/lock';
import { pushMutations } from '@/lib/api/v1/sync/push';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

const tooLarge = () => new ApiError(413, 'PAYLOAD_TOO_LARGE', 'Request body is too large', false);

function assertJsonContentType(request: Request): void {
  const type = request.headers.get('content-type')?.split(';')[0].trim().toLowerCase();
  if (type !== 'application/json') {
    throw new ApiError(415, 'UNSUPPORTED_MEDIA_TYPE', 'Content-Type must be application/json', false);
  }
}

/** Lee el cuerpo cortando en cuanto supera el tope: content-length puede faltar o mentir. */
async function readBodyCapped(request: Request, maxBytes: number): Promise<string> {
  const declared = Number(request.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > maxBytes) throw tooLarge();
  if (!request.body) return '';

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      throw tooLarge();
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    throw new ApiError(400, 'INVALID_JSON', 'Request body is not valid JSON', false);
  }
}

export const POST = withApiRoute(async (request, principal) => {
  await enforceRateLimit('sync', principal.userId);
  assertJsonContentType(request);
  const body = PushRequestSchema.parse(parseJson(await readBodyCapped(request, MAX_PUSH_BODY_BYTES)));

  // READ COMMITTED (el defecto, explícito): la reclamación de dedupe relee filas confirmadas por
  // entregas concurrentes. El lock por usuario va primero: ordena la asignación de secuencias.
  const result = await getPoolDb().transaction(
    async (tx) => {
      await lockUserSync(tx, principal.userId);
      return pushMutations(tx, principal, body);
    },
    { isolationLevel: 'read committed' },
  );
  return Response.json(result, { headers: { 'cache-control': 'no-store' } });
});
