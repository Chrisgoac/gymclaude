// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { GooglePlayUnavailableError, obfuscatedAccountIdFor, type GooglePlayClient, type GooglePlaySubscription } from '@/lib/billing/google-play';
import { hashPurchaseToken } from '@/lib/billing/entitlements';
import {
  RtdnAuthUnavailableError,
  type PubSubAuthVerifier,
  type RtdnRuntime,
  type RtdnStore,
} from '@/lib/billing/rtdn';

const h = vi.hoisted(() => ({ runtime: undefined as unknown as RtdnRuntime }));
vi.mock('@/lib/billing/rtdn', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/billing/rtdn')>()),
  getRtdnRuntime: () => h.runtime,
}));

import { POST } from './route';

const NOW = 1_767_225_600_000;
const DAY = 86_400_000;
const PACKAGE = 'com.chrisgoac.gymlog';
const USER = 'user_1';
const TOKEN = 'rtdn.route-secret-token';
const JWT = 'eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl';

const sub = (): GooglePlaySubscription => ({
  subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
  lineItems: [{ productId: 'gymlog_pro_monthly', expiryTime: new Date(NOW + 30 * DAY).toISOString(), autoRenewingPlan: { autoRenewEnabled: true } }],
  externalAccountIdentifiers: { obfuscatedExternalAccountId: obfuscatedAccountIdFor(USER) },
});

function makeStore() {
  const processed = new Set<string>();
  const commits: string[] = [];
  const store: RtdnStore = {
    isProcessed: vi.fn(async (id: string) => processed.has(id)),
    recordReceived: vi.fn(async () => {}),
    findByTokenHash: vi.fn(async (hash: string) =>
      hash === hashPurchaseToken(TOKEN) ? { userId: USER, productId: 'gymlog_pro_monthly', expiresAt: NOW + DAY } : null),
    commit: vi.fn(async (c) => {
      if (processed.has(c.event.messageId)) return 'duplicate' as const;
      processed.add(c.event.messageId);
      commits.push(c.outcome);
      return 'committed' as const;
    }),
  };
  return { store, commits };
}

let getSubscription: ReturnType<typeof vi.fn<GooglePlayClient['getSubscription']>>;
let verify: ReturnType<typeof vi.fn<PubSubAuthVerifier['verify']>>;
let store: ReturnType<typeof makeStore>;

function runtime(over: Partial<RtdnRuntime> = {}): RtdnRuntime {
  return {
    verifier: { verify },
    play: { packageName: PACKAGE, client: { getSubscription, acknowledgeSubscription: vi.fn(async () => {}) } },
    store: store.store,
    now: () => NOW,
    ...over,
  };
}

const data = (body: unknown) => Buffer.from(JSON.stringify(body)).toString('base64');
const renewal = () => data({ version: '1.0', packageName: PACKAGE, eventTimeMillis: String(NOW),
  subscriptionNotification: { version: '1.0', notificationType: 2, purchaseToken: TOKEN, subscriptionId: 'gymlog_pro_monthly' } });

function push(body: unknown, init: { auth?: string | null; contentType?: string } = {}) {
  const headers = new Headers({ 'content-type': init.contentType ?? 'application/json' });
  if (init.auth !== null) headers.set('authorization', init.auth ?? `Bearer ${JWT}`);
  return new Request('https://gym.example/api/webhooks/google-play', {
    method: 'POST', headers, body: typeof body === 'string' ? body : JSON.stringify(body),
  });
}
const envelope = (messageId = 'msg-1', d = renewal()) => ({ message: { data: d, messageId, publishTime: new Date(NOW).toISOString() }, subscription: 'projects/p/subscriptions/s' });

let logs: MockInstance[];
beforeEach(() => {
  getSubscription = vi.fn<GooglePlayClient['getSubscription']>(async () => sub());
  verify = vi.fn<PubSubAuthVerifier['verify']>(async (header) => (header === `Bearer ${JWT}` ? 'ok' : 'invalid'));
  store = makeStore();
  h.runtime = runtime();
  logs = ['info', 'warn', 'error', 'log'].map((m) => vi.spyOn(console, m as 'info').mockImplementation(() => {}));
});
afterEach(() => {
  const printed = logs.flatMap((s) => s.mock.calls.map((c) => JSON.stringify(c))).join('\n');
  expect(printed).not.toContain(TOKEN);
  expect(printed).not.toContain(JWT);
  vi.restoreAllMocks();
});

describe('POST /api/webhooks/google-play', () => {
  it('processes an authenticated renewal and acknowledges with 200', async () => {
    const res = await POST(push(envelope()));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ outcome: 'updated' });
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(verify).toHaveBeenCalledWith(`Bearer ${JWT}`);
    expect(getSubscription).toHaveBeenCalledWith(PACKAGE, TOKEN);
    expect(store.commits).toEqual(['updated']);
  });

  it('rejects forged or missing tokens with 401 before reading the body', async () => {
    for (const auth of [null, 'Bearer forged.jwt.value', 'Basic x']) {
      const res = await POST(push(envelope(), { auth }));
      expect(res.status).toBe(401);
    }
    expect(store.store.isProcessed).not.toHaveBeenCalled();
    expect(getSubscription).not.toHaveBeenCalled();
  });

  it('answers 503 (Pub/Sub retries) when push auth or Play is not configured, or Google keys are down', async () => {
    h.runtime = runtime({ verifier: null });
    expect((await POST(push(envelope()))).status).toBe(503);
    h.runtime = runtime({ play: null });
    expect((await POST(push(envelope()))).status).toBe(503);
    verify.mockRejectedValueOnce(new RtdnAuthUnavailableError());
    h.runtime = runtime();
    expect((await POST(push(envelope()))).status).toBe(503);
    expect(getSubscription).not.toHaveBeenCalled();
  });

  it('malformed envelopes are 400; bad JSON 400; wrong content type 415', async () => {
    expect((await POST(push({ nope: true }))).status).toBe(400);
    expect((await POST(push('{not json'))).status).toBe(400);
    expect((await POST(push(envelope(), { contentType: 'text/plain' }))).status).toBe(415);
  });

  it('malformed notification data is recorded and acknowledged', async () => {
    const res = await POST(push(envelope('msg-bad', '!!!')));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ outcome: 'malformed' });
  });

  it('a duplicate delivery is acknowledged without reprocessing', async () => {
    await POST(push(envelope('dup')));
    const res = await POST(push(envelope('dup')));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ outcome: 'duplicate' });
    expect(getSubscription).toHaveBeenCalledTimes(1);
  });

  it('a transient Google error answers 503 and the redelivery succeeds', async () => {
    getSubscription.mockRejectedValueOnce(new GooglePlayUnavailableError('subscriptionsv2.get: http 500'));
    expect((await POST(push(envelope('retry')))).status).toBe(503);
    const res = await POST(push(envelope('retry')));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ outcome: 'updated' });
  });

  it('an unknown token is acknowledged without a grant', async () => {
    const other = data({ version: '1.0', packageName: PACKAGE, eventTimeMillis: String(NOW),
      subscriptionNotification: { version: '1.0', notificationType: 4, purchaseToken: 'unknown-token', subscriptionId: 'gymlog_pro_monthly' } });
    const res = await POST(push(envelope('unknown', other)));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ outcome: 'unknown_token' });
  });
});
