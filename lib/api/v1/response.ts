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
  console.error(`[api/v1] unhandled error requestId=${requestId}`, error);
  return jsonResponse(
    { error: { code: 'INTERNAL_ERROR', message: 'Internal server error', retryable: true } } satisfies ApiErrorBody,
    requestId,
    500,
  );
}
