import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiErrorBodySchema, PullResponseSchema } from '@/lib/api/v1/contracts/sync';
import { setAuthSourceForTests } from '@/lib/api/v1/auth';
import { ApiError, RateLimitedError } from '@/lib/api/v1/errors';

const fakeTx = { tag: 'tx' };
const transaction = vi.fn(async (...args: [fn: (tx: unknown) => Promise<unknown>, config?: unknown]) => args[0](fakeTx));
vi.mock('@/db/pool', () => ({ getPoolDb: () => ({ transaction }) }));
vi.mock('@/lib/api/v1/rate-limit', () => ({ enforceRateLimit: vi.fn(async () => {}) }));
vi.mock('@/lib/api/v1/sync/pull', () => ({
  pullChanges: vi.fn(async () => ({
    changes: [
      {
        table: 'gyms',
        serverVersion: '42',
        record: { id: 'gym-1', updatedAt: 1, deletedAt: null, nombre: 'G', orden: 0, archivada: false },
      },
    ],
    cursor: 'Mjk',
    hasMore: false,
  })),
}));

import { GET, MAX_PULL_LIMIT } from './route';
import { enforceRateLimit } from '@/lib/api/v1/rate-limit';
import { pullChanges } from '@/lib/api/v1/sync/pull';

const get = (query = '') => GET(new Request(`http://x/api/v1/sync/pull${query}`));
const errorOf = async (res: Response) => ApiErrorBodySchema.parse(await res.json()).error;

beforeEach(() => setAuthSourceForTests(async () => ({ userId: 'user_1' })));
afterEach(() => {
  setAuthSourceForTests(null);
  vi.clearAllMocks();
});

describe('GET /api/v1/sync/pull', () => {
  it('defaults to limit 500 and a null cursor, delegating to pullChanges', async () => {
    const res = await get();
    expect(res.status).toBe(200);
    const body = PullResponseSchema.parse(await res.json());
    expect(body.changes[0].serverVersion).toBe('42');
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(MAX_PULL_LIMIT).toBe(500);
    expect(enforceRateLimit).toHaveBeenCalledWith('sync', 'user_1');
    expect(pullChanges).toHaveBeenCalledWith(fakeTx, { userId: 'user_1' }, null, 500);
  });

  it('forwards cursor and limit', async () => {
    await get('?cursor=Mjk&limit=25');
    expect(pullChanges).toHaveBeenCalledWith(fakeTx, { userId: 'user_1' }, 'Mjk', 25);
  });

  it('caps limits above 500', async () => {
    await get('?limit=10000');
    expect(pullChanges).toHaveBeenCalledWith(fakeTx, { userId: 'user_1' }, null, 500);
  });

  it.each(['0', '-1', '1.5', 'abc', '', '1e2'])('400 INVALID_REQUEST for limit=%s', async (limit) => {
    const res = await get(`?limit=${limit}`);
    expect(res.status).toBe(400);
    expect((await errorOf(res)).code).toBe('INVALID_REQUEST');
    expect(pullChanges).not.toHaveBeenCalled();
  });

  it('401 AUTH_REQUIRED without a bearer session', async () => {
    setAuthSourceForTests(async () => ({ userId: null }));
    const res = await get();
    expect(res.status).toBe(401);
    expect((await errorOf(res)).code).toBe('AUTH_REQUIRED');
    expect(enforceRateLimit).not.toHaveBeenCalled();
  });

  it('429 when rate limited', async () => {
    vi.mocked(enforceRateLimit).mockRejectedValueOnce(new RateLimitedError(3));
    const res = await get();
    expect(res.status).toBe(429);
    expect(res.headers.get('retry-after')).toBe('3');
    expect(pullChanges).not.toHaveBeenCalled();
  });

  it('serializes service errors (invalid cursor) as stable JSON', async () => {
    vi.mocked(pullChanges).mockRejectedValueOnce(new ApiError(400, 'INVALID_CURSOR', 'Invalid cursor', false));
    const res = await get('?cursor=!!!');
    expect(res.status).toBe(400);
    expect(await errorOf(res)).toEqual({ code: 'INVALID_CURSOR', message: 'Invalid cursor', retryable: false });
  });
});
