import { ApiError } from '@/lib/api/v1/errors';
import { RecordIdSchema } from '@/lib/api/v1/contracts/common';
import { MEDIA_LIMITS, type MediaKind } from './contracts';
import type { MediaUploadInput } from './service';

// Capa HTTP compartida de las rutas de medios v1. Vive fuera de los route.ts (un route handler
// de Next solo puede exportar campos de ruta conocidos).

/** Margen para boundaries, cabeceras de parte y campos de texto del multipart. */
export const MULTIPART_OVERHEAD_BYTES = 64 * 1024;
export const MAX_MEDIA_BODY_BYTES = MEDIA_LIMITS.maxBytes + MULTIPART_OVERHEAD_BYTES;

const tooLarge = () => new ApiError(413, 'MEDIA_TOO_LARGE', `Media exceeds ${MEDIA_LIMITS.maxBytes} bytes`, false);
const cancelled = () => new ApiError(499, 'REQUEST_CANCELLED', 'Request was cancelled', true);
const badMultipart = (message: string) => new ApiError(400, 'INVALID_MULTIPART', message, false);

export function assertNotCancelled(request: Request): void {
  if (request.signal.aborted) throw cancelled();
}

/**
 * Lee el cuerpo cortando en cuanto supera el tope (Content-Length puede faltar o mentir) y
 * abandona la lectura si el cliente cancela. Nunca acumula más de `maxBytes` + un chunk.
 */
export async function readBodyCapped(request: Request, maxBytes: number): Promise<Uint8Array> {
  const declared = request.headers.get('content-length');
  if (declared !== null && Number(declared) > maxBytes) throw tooLarge();
  assertNotCancelled(request);
  if (!request.body) return new Uint8Array(0);

  const reader = request.body.getReader();
  const onAbort = () => void reader.cancel().catch(() => {});
  request.signal.addEventListener('abort', onAbort, { once: true });
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      let result: ReadableStreamReadResult<Uint8Array>;
      try {
        result = await reader.read();
      } catch (e) {
        if (request.signal.aborted) throw cancelled();
        throw e;
      }
      if (request.signal.aborted) throw cancelled();
      if (result.done) break;
      total += result.value.byteLength;
      if (total > maxBytes) {
        await reader.cancel().catch(() => {});
        throw tooLarge();
      }
      chunks.push(result.value);
    }
  } finally {
    request.signal.removeEventListener('abort', onAbort);
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

/** Lee y parsea una subida multipart (parte `file` + campos de texto) con los topes aplicados antes. */
export async function readMediaUpload(request: Request, kind: MediaKind): Promise<MediaUploadInput> {
  const contentType = request.headers.get('content-type') ?? '';
  if (contentType.split(';')[0].trim().toLowerCase() !== 'multipart/form-data') {
    throw new ApiError(415, 'UNSUPPORTED_MEDIA_TYPE', 'Content-Type must be multipart/form-data', false);
  }
  const body = await readBodyCapped(request, MAX_MEDIA_BODY_BYTES);

  let form: FormData;
  try {
    form = await new Response(body as BodyInit, { headers: { 'content-type': contentType } }).formData();
  } catch {
    throw badMultipart('Request body is not valid multipart/form-data');
  }

  const fields: Record<string, unknown> = {};
  let file: Blob | null = null;
  for (const [name, value] of form.entries()) {
    if (name === 'file') {
      if (typeof value === 'string' || file) throw badMultipart('Exactly one "file" part is required');
      file = value;
    } else {
      if (typeof value !== 'string') throw badMultipart(`Field "${name}" must be text`);
      if (name in fields) throw badMultipart(`Field "${name}" is repeated`);
      fields[name] = name === 'fecha' && /^-?\d{1,16}$/.test(value) ? Number(value) : value;
    }
  }
  if (!file) throw badMultipart('Exactly one "file" part is required');

  const bytes = new Uint8Array(await file.arrayBuffer());
  assertNotCancelled(request);
  return { kind, fields, bytes, declaredMime: file.type } as MediaUploadInput;
}

/** Último segmento de la ruta como id de registro (las rutas son `.../<id>`). */
export function mediaIdFromUrl(request: Request): string {
  const segments = new URL(request.url).pathname.split('/').filter(Boolean);
  let raw = segments[segments.length - 1] ?? '';
  try {
    raw = decodeURIComponent(raw);
  } catch {
    /* se rechaza abajo */
  }
  const parsed = RecordIdSchema.safeParse(raw);
  if (!parsed.success) throw new ApiError(400, 'INVALID_MEDIA_ID', 'Invalid media id', false);
  return parsed.data;
}
