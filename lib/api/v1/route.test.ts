import { afterEach, describe, expect, it, vi } from 'vitest';
// The account-deletion access lock has its own tests (app/api/v1/account, lib/account).
vi.mock('@/lib/account/access', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/account/access')>()),
  assertAccountActive: async () => {},
}));
import { z } from 'zod';
import { ApiErrorBodySchema } from './contracts/common';
import { setAuthSourceForTests } from './auth';
import { ApiError, RateLimitedError } from './errors';
import { withApiRoute } from './route';

const req = (headers: Record<string, string> = {}) => new Request('http://x/api/v1/t', { headers });
const signedIn = () => setAuthSourceForTests(async () => ({ userId: 'user_1' }));

afterEach(() => {
  setAuthSourceForTests(null);
  vi.restoreAllMocks();
});

describe('withApiRoute', () => {
  it('returns 401 AUTH_REQUIRED without a session', async () => {
    setAuthSourceForTests(async () => ({ userId: null }));
    const handler = vi.fn();
    const res = await withApiRoute(handler)(req());
    expect(res.status).toBe(401);
    expect(ApiErrorBodySchema.parse(await res.json()).error.code).toBe('AUTH_REQUIRED');
    expect(handler).not.toHaveBeenCalled();
  });

  it('returns 401 when the auth source throws (invalid token)', async () => {
    setAuthSourceForTests(async () => {
      throw new Error('bad token');
    });
    const res = await withApiRoute(async () => new Response('{}'))(req());
    expect(res.status).toBe(401);
  });

  it('passes principal and sets json content type + request id', async () => {
    signedIn();
    const res = await withApiRoute(async (_r, p) => Response.json({ u: p.userId }))(req());
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toContain('application/json');
    expect(res.headers.get('x-request-id')).toMatch(/^[0-9a-f-]{36}$/);
    expect(await res.json()).toEqual({ u: 'user_1' });
  });

  it('reuses a well-formed x-request-id and replaces a malformed one', async () => {
    signedIn();
    const route = withApiRoute(async () => Response.json({}));
    expect((await route(req({ 'x-request-id': 'abc-12345678' }))).headers.get('x-request-id')).toBe('abc-12345678');
    expect((await route(req({ 'x-request-id': 'bad id!' }))).headers.get('x-request-id')).not.toBe('bad id!');
  });

  it('serializes ApiError', async () => {
    signedIn();
    const res = await withApiRoute(async () => {
      throw new ApiError(409, 'CONFLICT', 'nope', false, { f: ['x'] });
    })(req());
    expect(res.status).toBe(409);
    expect(ApiErrorBodySchema.parse(await res.json())).toEqual({
      error: { code: 'CONFLICT', message: 'nope', retryable: false, details: { f: ['x'] } },
    });
  });

  it('maps ZodError to 400 INVALID_REQUEST with field details', async () => {
    signedIn();
    const res = await withApiRoute(async () => {
      z.object({ name: z.string() }).parse({});
      return Response.json({});
    })(req());
    expect(res.status).toBe(400);
    const body = ApiErrorBodySchema.parse(await res.json());
    expect(body.error.code).toBe('INVALID_REQUEST');
    expect(body.error.details?.name?.length).toBeGreaterThan(0);
  });

  it('maps unexpected errors to a non-leaky 500 and logs server-side', async () => {
    signedIn();
    const log = vi.spyOn(console, 'error').mockImplementation(() => {});
    const res = await withApiRoute(async () => {
      throw new Error('secret db password');
    })(req());
    expect(res.status).toBe(500);
    const text = await res.text();
    expect(text).not.toContain('secret');
    expect(ApiErrorBodySchema.parse(JSON.parse(text)).error.code).toBe('INTERNAL_ERROR');
    expect(log).toHaveBeenCalled();
  });

  it('serializes RateLimitedError with Retry-After', async () => {
    signedIn();
    const res = await withApiRoute(async () => {
      throw new RateLimitedError(17);
    })(req());
    expect(res.status).toBe(429);
    expect(res.headers.get('retry-after')).toBe('17');
    const body = ApiErrorBodySchema.parse(await res.json());
    expect(body.error).toMatchObject({ code: 'RATE_LIMITED', retryable: true, details: { retryAfterSeconds: ['17'] } });
  });
});
