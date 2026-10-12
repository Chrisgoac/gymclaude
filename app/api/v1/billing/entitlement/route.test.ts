// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
// The account-deletion access lock has its own tests (app/api/v1/account, lib/account).
vi.mock('@/lib/account/access', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/account/access')>()),
  assertAccountActive: async () => {},
}));
import { setAuthSourceForTests } from '@/lib/api/v1/auth';
import { RateLimitedError } from '@/lib/api/v1/errors';
import { ApiErrorBodySchema } from '@/lib/api/v1/contracts/common';
import { EntitlementResponseSchema, type BillingDeps, type EntitlementRow } from '@/lib/billing/entitlements';

const h = vi.hoisted(() => ({
  deps: undefined as unknown as BillingDeps,
  rate: vi.fn<(...args: unknown[]) => Promise<void>>(async () => {}),
}));
vi.mock('@/lib/api/v1/rate-limit', () => ({ enforceRateLimit: (...a: unknown[]) => h.rate(...a) }));
vi.mock('@/lib/billing/entitlements', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/billing/entitlements')>()),
  getBillingDeps: () => h.deps,
}));

import { GET } from './route';

const USER = 'user_1';
const NOW = 1_767_225_600_000;
const DAY = 86_400_000;

const row = (over: Partial<EntitlementRow> = {}): EntitlementRow => ({
  userId: USER, productId: 'gymlog_pro_monthly', purchaseTokenHash: 'hash', state: 'GRACE', expiresAt: NOW + DAY,
  autoRenewing: true, lastVerifiedAt: NOW, source: 'play', ...over,
});

function deps(best: EntitlementRow | null | Error): BillingDeps {
  return {
    play: null, // reading the entitlement never needs Google
    now: () => NOW,
    store: {
      upsertPlay: vi.fn(),
      findBest: vi.fn(async () => { if (best instanceof Error) throw best; return best; }),
    },
  };
}

const get = () => GET(new Request('http://x/api/v1/billing/entitlement'));

beforeEach(() => {
  setAuthSourceForTests(async () => ({ userId: USER }));
  h.rate.mockReset().mockResolvedValue(undefined);
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => {
  setAuthSourceForTests(null);
  vi.restoreAllMocks();
});

describe('GET /api/v1/billing/entitlement', () => {
  it("answers the caller's best entitlement with server time, uncached", async () => {
    h.deps = deps(row());
    const res = await get();
    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(EntitlementResponseSchema.parse(await res.json())).toEqual({
      tier: 'PRO', productId: 'gymlog_pro_monthly', state: 'GRACE', validUntil: NOW + DAY, serverTime: NOW,
    });
    expect(h.deps.store.findBest).toHaveBeenCalledWith(USER, NOW);
    expect(h.rate).toHaveBeenCalledWith('billing', USER);
  });

  it('a user without purchases is FREE/NONE', async () => {
    h.deps = deps(null);
    expect(await (await get()).json()).toEqual({ tier: 'FREE', productId: null, state: 'NONE', validUntil: null, serverTime: NOW });
  });

  it('401 without a session; 429 when rate limited', async () => {
    h.deps = deps(null);
    setAuthSourceForTests(async () => ({ userId: null }));
    expect((await get()).status).toBe(401);
    expect(h.deps.store.findBest).not.toHaveBeenCalled();

    setAuthSourceForTests(async () => ({ userId: USER }));
    h.rate.mockRejectedValueOnce(new RateLimitedError(5));
    expect((await get()).status).toBe(429);
  });

  it('a store failure is a retryable 503, never a FREE answer', async () => {
    h.deps = deps(new Error('connection refused'));
    const res = await get();
    expect(res.status).toBe(503);
    expect(ApiErrorBodySchema.parse(await res.json()).error).toMatchObject({ code: 'ENTITLEMENT_UNAVAILABLE', retryable: true });
  });
});
