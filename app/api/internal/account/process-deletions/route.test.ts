// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => ({ resumePending: vi.fn() }));
vi.mock('@/lib/account/runtime', () => ({ getAccountDeletionService: () => ({ resumePending: h.resumePending }) }));

import { GET, POST } from './route';

const call = (handler: typeof GET, auth?: string) =>
  handler(new Request('http://x/api/internal/account/process-deletions', auth ? { headers: { authorization: auth } } : {}));

beforeEach(() => {
  vi.stubEnv('CRON_SECRET', 'cron-test-secret');
  h.resumePending.mockReset().mockResolvedValue({ deleted: 2, pending: 1 });
});
afterEach(() => vi.unstubAllEnvs());

describe('/api/internal/account/process-deletions', () => {
  it.each([undefined, 'Bearer wrong'])('401 without the cron secret (%s), resuming nothing', async (auth) => {
    expect((await call(GET, auth)).status).toBe(401);
    expect(h.resumePending).not.toHaveBeenCalled();
  });

  it('401 when CRON_SECRET is not configured', async () => {
    vi.stubEnv('CRON_SECRET', '');
    expect((await call(GET, 'Bearer ')).status).toBe(401);
  });

  it.each([['GET', GET], ['POST', POST]] as const)('%s resumes a bounded batch of stuck jobs', async (_m, handler) => {
    const res = await call(handler, 'Bearer cron-test-secret');
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ deleted: 2, pending: 1 });
    expect(h.resumePending).toHaveBeenCalledWith({ limit: 10 });
  });
});
