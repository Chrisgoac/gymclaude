import { ApiError } from '@/lib/api/v1/errors';

// HTTP helpers shared by the /api/v1/ai/* routes (a Next route file may only export route fields).

/** Body cap of AI JSON requests: MAX_COACH_REQUEST_BYTES plus room for JSON escaping. */
export const MAX_AI_BODY_BYTES = 64 * 1024;

const tooLarge = () => new ApiError(413, 'PAYLOAD_TOO_LARGE', 'Request body is too large', false);
const invalidJson = () => new ApiError(400, 'INVALID_JSON', 'Request body is not valid JSON', false);

/** Reads a JSON body (Content-Type application/json), cutting off as soon as it exceeds `maxBytes`. */
export async function readJsonBody(request: Request, maxBytes = MAX_AI_BODY_BYTES): Promise<unknown> {
  const type = request.headers.get('content-type')?.split(';')[0].trim().toLowerCase();
  if (type !== 'application/json') {
    throw new ApiError(415, 'UNSUPPORTED_MEDIA_TYPE', 'Content-Type must be application/json', false);
  }
  const declared = Number(request.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > maxBytes) throw tooLarge();
  if (!request.body) throw invalidJson();

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel().catch(() => {});
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
  try {
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch {
    throw invalidJson();
  }
}

export const aiUnavailable = () => new ApiError(503, 'AI_UNAVAILABLE', 'The AI service is not configured', true);
