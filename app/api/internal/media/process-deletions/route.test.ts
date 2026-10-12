// @vitest-environment node
import { readFileSync } from 'node:fs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/lib/api/v1/errors';

const service = { processDeletions: vi.fn() };
vi.mock('@/lib/api/v1/media/runtime', () => ({ getMediaService: () => service }));

import { GET, POST } from './route';

const SECRET = 'cron-secret-test-value';
const call = (handler: typeof POST, authorization?: string) =>
  handler(
    new Request('http://x/api/internal/media/process-deletions', {
      method: handler === GET ? 'GET' : 'POST',
      headers: authorization === undefined ? {} : { authorization },
    }),
  );

beforeEach(() => {
  vi.stubEnv('CRON_SECRET', SECRET);
  service.processDeletions.mockResolvedValue({ deleted: 2, cancelled: 1, failed: 0 });
});
afterEach(() => {
  vi.unstubAllEnvs();
  vi.clearAllMocks();
});

describe('/api/internal/media/process-deletions', () => {
  it('401 without, or with a wrong, bearer secret and never processes', async () => {
    for (const auth of [undefined, '', `Bearer ${SECRET}x`, SECRET, `bearer ${SECRET}`, 'Bearer ']) {
      const res = await call(POST, auth);
      expect(res.status).toBe(401);
      expect((await res.json()).error.code).toBe('AUTH_REQUIRED');
    }
    expect(service.processDeletions).not.toHaveBeenCalled();
  });

  it('401 for everyone when CRON_SECRET is not configured', async () => {
    vi.stubEnv('CRON_SECRET', '');
    expect((await call(POST, 'Bearer ')).status).toBe(401);
    expect((await call(POST, 'Bearer undefined')).status).toBe(401);
    expect(service.processDeletions).not.toHaveBeenCalled();
  });

  it('processes one bounded batch with the cron secret (GET from Vercel cron, POST manual)', async () => {
    for (const handler of [GET, POST]) {
      const res = await call(handler, `Bearer ${SECRET}`);
      expect(res.status).toBe(200);
      expect(res.headers.get('cache-control')).toBe('no-store');
      expect(await res.json()).toEqual({ deleted: 2, cancelled: 1, failed: 0, batches: 1 });
    }
    expect(service.processDeletions).toHaveBeenCalledWith({ limit: 50 });
  });

  it('keeps claiming full batches up to a cap', async () => {
    service.processDeletions.mockResolvedValue({ deleted: 50, cancelled: 0, failed: 0 });
    const res = await call(POST, `Bearer ${SECRET}`);
    expect(await res.json()).toMatchObject({ deleted: 500, batches: 10 });
    expect(service.processDeletions).toHaveBeenCalledTimes(10);
  });

  it('maps storage misconfiguration to its API error', async () => {
    service.processDeletions.mockRejectedValue(new ApiError(503, 'MEDIA_UNAVAILABLE', 'x', true));
    const res = await call(POST, `Bearer ${SECRET}`);
    expect(res.status).toBe(503);
    expect((await res.json()).error.code).toBe('MEDIA_UNAVAILABLE');
  });

  it('is scheduled by the Vercel cron in vercel.json', () => {
    const config = JSON.parse(readFileSync('vercel.json', 'utf8')) as { crons?: { path: string; schedule: string }[] };
    expect(config.crons).toContainEqual(expect.objectContaining({ path: '/api/internal/media/process-deletions' }));
  });
});
