// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
// The account-deletion access lock has its own tests (app/api/v1/account, lib/account).
vi.mock('@/lib/account/access', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/account/access')>()),
  assertAccountActive: async () => {},
}));
import { setAuthSourceForTests } from '@/lib/api/v1/auth';
import { RateLimitedError } from '@/lib/api/v1/errors';
import { ApiErrorBodySchema } from '@/lib/api/v1/contracts/common';
import {
  GooglePlayUnavailableError,
  obfuscatedAccountIdFor,
  type GooglePlayClient,
  type GooglePlaySubscription,
} from '@/lib/billing/google-play';
import {
  EntitlementResponseSchema,
  hashPurchaseToken,
  type BillingDeps,
  type EntitlementRow,
  type EntitlementStore,
} from '@/lib/billing/entitlements';

const h = vi.hoisted(() => ({
  deps: undefined as unknown as BillingDeps,
  rate: vi.fn<(...args: unknown[]) => Promise<void>>(async () => {}),
}));
vi.mock('@/lib/api/v1/rate-limit', () => ({ enforceRateLimit: (...a: unknown[]) => h.rate(...a) }));
vi.mock('@/lib/billing/entitlements', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/billing/entitlements')>()),
  getBillingDeps: () => h.deps,
}));

import { POST } from './route';

const USER = 'user_1';
const OTHER = 'user_2';
const NOW = 1_767_225_600_000;
const DAY = 86_400_000;
const PACKAGE = 'com.chrisgoac.gymlog';
const TOKEN = 'tok.AO-J1Oz_route-secret-token';

const sub = (userId = USER, over: Partial<GooglePlaySubscription> = {}): GooglePlaySubscription => ({
  subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
  acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
  lineItems: [{ productId: 'gymlog_pro_annual', expiryTime: new Date(NOW + 365 * DAY).toISOString(), autoRenewingPlan: { autoRenewEnabled: true } }],
  externalAccountIdentifiers: { obfuscatedExternalAccountId: obfuscatedAccountIdFor(userId) },
  ...over,
});

function deps(getSubscription: GooglePlayClient['getSubscription'], rows: EntitlementRow[] = []) {
  const client: GooglePlayClient = { getSubscription: vi.fn(getSubscription), acknowledgeSubscription: vi.fn(async () => {}) };
  const store: EntitlementStore = {
    upsertPlay: vi.fn(async (w) => {
      if (rows.some((r) => r.purchaseTokenHash === w.purchaseTokenHash && r.userId !== w.userId)) return 'already_bound';
      rows.push({ userId: w.userId, productId: w.productId, purchaseTokenHash: w.purchaseTokenHash, state: w.state,
        expiresAt: w.expiresAt, autoRenewing: w.autoRenewing, lastVerifiedAt: w.verifiedAt, source: 'play' });
      return 'bound';
    }),
    findBest: vi.fn(async (userId) => rows.find((r) => r.userId === userId) ?? null),
  };
  return { play: { packageName: PACKAGE, client }, store, now: () => NOW } satisfies BillingDeps;
}

const post = (payload: unknown, init: RequestInit = {}) =>
  POST(new Request('http://x/api/v1/billing/verify', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: typeof payload === 'string' ? payload : JSON.stringify(payload),
    ...init,
  }));
const body = (over: Record<string, unknown> = {}) => ({ productId: 'gymlog_pro_annual', purchaseToken: TOKEN, ...over });
const errorOf = async (res: Response) => ApiErrorBodySchema.parse(await res.json()).error;

let errorSpy: MockInstance;
beforeEach(() => {
  setAuthSourceForTests(async () => ({ userId: USER }));
  h.deps = deps(async () => sub());
  h.rate.mockReset().mockResolvedValue(undefined);
  errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.spyOn(console, 'log').mockImplementation(() => {});
});
afterEach(() => {
  setAuthSourceForTests(null);
  vi.restoreAllMocks();
});

describe('POST /api/v1/billing/verify', () => {
  it('verifies, projects and answers the caller entitlement with server time', async () => {
    const res = await post(body());
    const raw = await res.text();
    expect(res.status, raw).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(res.headers.get('x-request-id')).toBeTruthy();
    expect(EntitlementResponseSchema.parse(JSON.parse(raw))).toEqual({
      tier: 'PRO', productId: 'gymlog_pro_annual', state: 'ACTIVE', validUntil: NOW + 365 * DAY, serverTime: NOW,
    });
    expect(h.rate).toHaveBeenCalledWith('billing', USER);
    expect(h.deps.store.upsertPlay).toHaveBeenCalledWith(expect.objectContaining({
      userId: USER, purchaseTokenHash: hashPurchaseToken(TOKEN), productId: 'gymlog_pro_annual',
    }));
  });

  it('401 before rate limit, body or Google', async () => {
    setAuthSourceForTests(async () => ({ userId: null }));
    const res = await post(body());
    expect(res.status).toBe(401);
    expect((await errorOf(res)).code).toBe('AUTH_REQUIRED');
    expect(h.rate).not.toHaveBeenCalled();
    expect(h.deps.play!.client.getSubscription).not.toHaveBeenCalled();
  });

  it('429 from the billing rate limit before reading the body or calling Google', async () => {
    h.rate.mockRejectedValueOnce(new RateLimitedError(30));
    const res = await post(body());
    expect(res.status).toBe(429);
    expect(res.headers.get('retry-after')).toBe('30');
    expect(h.deps.play!.client.getSubscription).not.toHaveBeenCalled();
  });

  it('strict body: unknown product, unbounded token, client-sent entitlement fields, bad JSON/type', async () => {
    for (const payload of [
      body({ productId: 'gymlog_pro_weekly' }),
      body({ purchaseToken: 'x'.repeat(5000) }),
      body({ userId: OTHER }),
      body({ tier: 'PRO' }),
      { productId: 'gymlog_pro_annual' },
    ]) {
      const res = await post(payload);
      expect(res.status, JSON.stringify(payload).slice(0, 60)).toBe(400);
      expect((await errorOf(res)).code).toBe('INVALID_REQUEST');
    }
    expect((await errorOf(await post('{oops'))).code).toBe('INVALID_JSON');
    expect((await post(body(), { headers: { 'content-type': 'text/plain' } })).status).toBe(415);
    expect(h.deps.play!.client.getSubscription).not.toHaveBeenCalled();
  });

  it('503 BILLING_UNAVAILABLE when Play is not configured', async () => {
    h.deps = { ...h.deps, play: null };
    const res = await post(body());
    expect(res.status).toBe(503);
    expect(await errorOf(res)).toMatchObject({ code: 'BILLING_UNAVAILABLE', retryable: true });
  });

  it('503 BILLING_UNAVAILABLE on a Google outage', async () => {
    h.deps = deps(async () => { throw new GooglePlayUnavailableError('http 500'); });
    const res = await post(body());
    expect(res.status).toBe(503);
    expect(await errorOf(res)).toMatchObject({ code: 'BILLING_UNAVAILABLE', retryable: true });
  });

  it('403 when the purchase belongs to another account, 409 when the token is already bound elsewhere', async () => {
    h.deps = deps(async () => sub(OTHER));
    expect(await errorOf(await post(body()))).toMatchObject({ code: 'PURCHASE_ACCOUNT_MISMATCH', retryable: false });

    const rows: EntitlementRow[] = [{
      userId: OTHER, productId: 'gymlog_pro_annual', purchaseTokenHash: hashPurchaseToken(TOKEN), state: 'ACTIVE',
      expiresAt: NOW + DAY, autoRenewing: true, lastVerifiedAt: NOW, source: 'play',
    }];
    h.deps = deps(async () => sub(USER), rows);
    const res = await post(body());
    expect(res.status).toBe(409);
    expect(await errorOf(res)).toMatchObject({ code: 'PURCHASE_ALREADY_BOUND', retryable: false });
    expect(rows.filter((r) => r.userId === USER)).toEqual([]);
  });

  it('422 for a token Google does not know (other package) or a different product', async () => {
    h.deps = deps(async () => null);
    expect(await errorOf(await post(body()))).toMatchObject({ code: 'PURCHASE_INVALID', retryable: false });
    h.deps = deps(async () => sub());
    const res = await post(body({ productId: 'gymlog_pro_monthly' }));
    expect(res.status).toBe(422);
  });

  it('503 ENTITLEMENT_UNAVAILABLE when the store fails, never logging the token or its hash', async () => {
    h.deps.store.upsertPlay = vi.fn(async () => {
      throw new Error(`duplicate key (user_id, purchase_token_hash)=(${USER}, ${hashPurchaseToken(TOKEN)}) token ${TOKEN}`);
    });
    const res = await post(body());
    expect(res.status).toBe(503);
    expect(await errorOf(res)).toMatchObject({ code: 'ENTITLEMENT_UNAVAILABLE', retryable: true });
    const logged = errorSpy.mock.calls.flat().map((a) => (a instanceof Error ? `${a.message} ${a.stack}` : String(a))).join('\n');
    expect(logged).not.toContain(TOKEN);
    expect(logged).not.toContain(hashPurchaseToken(TOKEN));
  });
});
