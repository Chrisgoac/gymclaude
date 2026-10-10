import { ZodError } from 'zod';
import { ApiError, RateLimitedError } from './errors';
import type { ApiErrorBody } from './contracts/common';

export function jsonResponse(body: unknown, requestId: string, status = 200, headers: HeadersInit = {}): Response {
  const h = new Headers(headers);
  h.set('content-type', 'application/json');
  h.set('x-request-id', requestId);
  h.set('cache-control', 'no-store');
  return new Response(JSON.stringify(body), { status, headers: h });
}

// Deadlock y fallo de serialización de PostgreSQL: el cliente puede reintentar tal cual.
const RETRYABLE_PG_CODES = new Set(['40P01', '40001']);

/** Busca el SQLSTATE en la cadena de `cause` (Drizzle envuelve el error de `pg`). */
function isRetryablePgError(error: unknown): boolean {
  for (let e: unknown = error, depth = 0; e instanceof Error && depth < 5; e = e.cause, depth++) {
    const code = (e as { code?: unknown }).code;
    if (typeof code === 'string' && RETRYABLE_PG_CODES.has(code)) return true;
  }
  return false;
}

export function errorResponse(error: unknown, requestId: string): Response {
  if (error instanceof ApiError) {
    const body: ApiErrorBody = {
      error: {
        code: error.code,
        message: error.message,
        retryable: error.retryable,
        ...(error.details ? { details: error.details } : {}),
      },
    };
    const headers: Record<string, string> = {};
    if (error instanceof RateLimitedError) headers['retry-after'] = String(error.retryAfterSeconds);
    return jsonResponse(body, requestId, error.status, headers);
  }
  if (error instanceof ZodError) {
    const flat = error.flatten();
    const details: Record<string, string[]> = { ...flat.fieldErrors } as Record<string, string[]>;
    if (flat.formErrors.length) details._ = flat.formErrors;
    return jsonResponse(
      { error: { code: 'INVALID_REQUEST', message: 'Invalid request', retryable: false, details } } satisfies ApiErrorBody,
      requestId,
      400,
    );
  }
  if (isRetryablePgError(error)) {
    return jsonResponse(
      { error: { code: 'RETRY_LATER', message: 'Temporary conflict, retry', retryable: true } } satisfies ApiErrorBody,
      requestId,
      503,
    );
  }
  console.error(`[api/v1] unhandled error requestId=${requestId}`, error);
  return jsonResponse(
    { error: { code: 'INTERNAL_ERROR', message: 'Internal server error', retryable: true } } satisfies ApiErrorBody,
    requestId,
    500,
  );
}
