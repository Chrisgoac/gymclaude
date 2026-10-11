// @vitest-environment node
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { createHash } from 'node:crypto';
import { sql } from 'drizzle-orm';
import type { PoolDb } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import {
  GooglePlayUnavailableError,
  obfuscatedAccountIdFor,
  type GooglePlayClient,
  type GooglePlaySubscription,
} from './google-play';
import {
  VerifyPurchaseRequestSchema,
  createDbEntitlementStore,
  hashPurchaseToken,
  readEntitlement,
  toEntitlementResponse,
  verifyPurchase,
  type EntitlementRow,
  type EntitlementStore,
  type PlayEntitlementWrite,
} from './entitlements';

const NOW = 1_767_225_600_000;
const DAY = 86_400_000;
const PACKAGE = 'com.chrisgoac.gymlog';
const USER = 'user_a';
const OTHER = 'user_b';
const TOKEN = 'tok.AO-J1Oz_valid-purchase-token.0123456789';

const iso = (ms: number) => new Date(ms).toISOString();

function subscription(over: Partial<GooglePlaySubscription> = {}, userId = USER): GooglePlaySubscription {
  return {
    subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
    acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    lineItems: [{ productId: 'gymlog_pro_monthly', expiryTime: iso(NOW + 30 * DAY), autoRenewingPlan: { autoRenewEnabled: true } }],
    externalAccountIdentifiers: { obfuscatedExternalAccountId: obfuscatedAccountIdFor(userId) },
    ...over,
  };
}

/** Fake Google: tokens exist only for PACKAGE; another package's token is unknown (null). */
function fakePlay(sub: GooglePlaySubscription | null | (() => never)) {
  const client = {
    getSubscription: vi.fn<GooglePlayClient['getSubscription']>(async (packageName) => {
      if (typeof sub === 'function') sub();
      return packageName === PACKAGE ? (sub as GooglePlaySubscription | null) : null;
    }),
    acknowledgeSubscription: vi.fn<GooglePlayClient['acknowledgeSubscription']>(async () => {}),
  } satisfies GooglePlayClient;
  return { packageName: PACKAGE, client };
}

/** In-memory store with the same binding semantics as the SQL store. */
function memoryStore() {
  const rows: EntitlementRow[] = [];
  const store = {
    rows,
    upsertPlay: vi.fn(async (w: PlayEntitlementWrite) => {
      if (rows.some((r) => r.purchaseTokenHash === w.purchaseTokenHash && r.userId !== w.userId)) return 'already_bound' as const;
      const existing = rows.find((r) => r.userId === w.userId && r.purchaseTokenHash === w.purchaseTokenHash);
      const next: EntitlementRow = {
        userId: w.userId, productId: w.productId, purchaseTokenHash: w.purchaseTokenHash, state: w.state,
        expiresAt: w.expiresAt, autoRenewing: w.autoRenewing, lastVerifiedAt: w.verifiedAt, source: 'play',
      };
      if (existing) Object.assign(existing, next);
      else rows.push(next);
      return 'bound' as const;
    }),
    findBest: vi.fn(async (userId: string, now: number) => {
      const mine = rows.filter((r) => r.userId === userId);
      const granting = mine.filter((r) => (r.state === 'ACTIVE' || r.state === 'GRACE') && r.expiresAt > now);
      const pool = granting.length ? granting : mine;
      return pool.sort((a, b) => b.expiresAt - a.expiresAt)[0] ?? null;
    }),
  } satisfies EntitlementStore & { rows: EntitlementRow[] };
  return store;
}

const req = (over: Record<string, unknown> = {}) =>
  VerifyPurchaseRequestSchema.parse({ productId: 'gymlog_pro_monthly', purchaseToken: TOKEN, ...over });

async function apiError(p: Promise<unknown>): Promise<ApiError> {
  const error = await p.then(() => null, (e: unknown) => e);
  expect(error).toBeInstanceOf(ApiError);
  return error as ApiError;
}

describe('VerifyPurchaseRequestSchema', () => {
  it('accepts exactly the two products and a bounded token', () => {
    expect(req({ productId: 'gymlog_pro_annual' }).productId).toBe('gymlog_pro_annual');
    for (const bad of [
      { productId: 'gymlog_pro_lifetime', purchaseToken: TOKEN },
      { productId: 'gymlog_pro_monthly', purchaseToken: '' },
      { productId: 'gymlog_pro_monthly', purchaseToken: 'x'.repeat(4097) },
      { productId: 'gymlog_pro_monthly', purchaseToken: 'has space' },
      { productId: 'gymlog_pro_monthly', purchaseToken: TOKEN, userId: USER },
      { productId: 'gymlog_pro_monthly', purchaseToken: TOKEN, state: 'ACTIVE' },
      { productId: 'gymlog_pro_monthly' },
    ]) {
      expect(VerifyPurchaseRequestSchema.safeParse(bad).success, JSON.stringify(bad).slice(0, 80)).toBe(false);
    }
  });
});

describe('hashPurchaseToken', () => {
  it('is the SHA-256 hex of the token, never the token', () => {
    expect(hashPurchaseToken(TOKEN)).toBe(createHash('sha256').update(TOKEN).digest('hex'));
    expect(hashPurchaseToken(TOKEN)).not.toContain(TOKEN);
  });
});

describe('verifyPurchase (fake Google)', () => {
  let errorSpy: MockInstance;
  let warnSpy: MockInstance;
  let logSpy: MockInstance;
  beforeEach(() => {
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
  });
  afterEach(() => vi.restoreAllMocks());

  const run = (play: ReturnType<typeof fakePlay>, store = memoryStore(), userId = USER, body = req()) =>
    verifyPurchase({ play, store, now: () => NOW }, userId, body);

  it('grants Pro for an active purchase bound to the caller, storing only the token hash', async () => {
    const play = fakePlay(subscription());
    const store = memoryStore();
    const res = await run(play, store);
    expect(res).toEqual({ tier: 'PRO', productId: 'gymlog_pro_monthly', state: 'ACTIVE', validUntil: NOW + 30 * DAY, serverTime: NOW });
    expect(play.client.getSubscription).toHaveBeenCalledWith(PACKAGE, TOKEN);
    expect(store.rows).toEqual([{
      userId: USER, productId: 'gymlog_pro_monthly', purchaseTokenHash: hashPurchaseToken(TOKEN), state: 'ACTIVE',
      expiresAt: NOW + 30 * DAY, autoRenewing: true, lastVerifiedAt: NOW, source: 'play',
    }]);
    expect(JSON.stringify(store.rows)).not.toContain(TOKEN);
  });

  it('a replayed token for the same user is idempotent (one row, same answer)', async () => {
    const play = fakePlay(subscription());
    const store = memoryStore();
    const first = await run(play, store);
    const second = await run(play, store);
    expect(second).toEqual(first);
    expect(store.rows).toHaveLength(1);
  });

  it('acknowledges a pending acknowledgement after persisting, and not when already acknowledged', async () => {
    const pending = fakePlay(subscription({ acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING' }));
    const store = memoryStore();
    const order: string[] = [];
    const upsert = store.upsertPlay.getMockImplementation()!;
    store.upsertPlay.mockImplementation(async (w) => { order.push('persist'); return upsert(w); });
    pending.client.acknowledgeSubscription.mockImplementation(async () => { order.push('acknowledge'); });
    await run(pending, store);
    expect(pending.client.acknowledgeSubscription).toHaveBeenCalledWith(PACKAGE, 'gymlog_pro_monthly', TOKEN);
    expect(order).toEqual(['persist', 'acknowledge']);

    const acked = fakePlay(subscription());
    await run(acked);
    expect(acked.client.acknowledgeSubscription).not.toHaveBeenCalled();
  });

  it('a failed acknowledgement does not fail the verified grant (retried on the next verify)', async () => {
    const play = fakePlay(subscription({ acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING' }));
    play.client.acknowledgeSubscription.mockRejectedValueOnce(new GooglePlayUnavailableError('http 503'));
    const res = await run(play);
    expect(res.tier).toBe('PRO');
  });

  it('rejects a token Google does not know for our package (wrong package) without writing', async () => {
    const play = { ...fakePlay(subscription()), packageName: 'com.other.app' };
    const store = memoryStore();
    const error = await apiError(run(play, store));
    expect([error.status, error.code, error.retryable]).toEqual([422, 'PURCHASE_INVALID', false]);
    expect(store.upsertPlay).not.toHaveBeenCalled();
  });

  it('rejects a product the purchase does not contain (wrong product)', async () => {
    const store = memoryStore();
    const error = await apiError(run(fakePlay(subscription()), store, USER, req({ productId: 'gymlog_pro_annual' })));
    expect([error.status, error.code]).toEqual([422, 'PURCHASE_INVALID']);
    expect(store.upsertPlay).not.toHaveBeenCalled();
  });

  it('rejects a purchase made for another account (obfuscated id mismatch or missing)', async () => {
    for (const ids of [{ obfuscatedExternalAccountId: obfuscatedAccountIdFor(OTHER) }, {}, undefined]) {
      const store = memoryStore();
      const error = await apiError(run(fakePlay(subscription({ externalAccountIdentifiers: ids })), store));
      expect([error.status, error.code, error.retryable]).toEqual([403, 'PURCHASE_ACCOUNT_MISMATCH', false]);
      expect(store.upsertPlay).not.toHaveBeenCalled();
    }
  });

  it('refuses a token already bound to another user (409, no grant to the caller)', async () => {
    const store = memoryStore();
    await run(fakePlay(subscription({}, USER)), store, USER);
    // Same token presented by another account whose obfuscated id Google also reports (defence in depth).
    const error = await apiError(run(fakePlay(subscription({}, OTHER)), store, OTHER));
    expect([error.status, error.code, error.retryable]).toEqual([409, 'PURCHASE_ALREADY_BOUND', false]);
    expect(store.rows.map((r) => r.userId)).toEqual([USER]);
    expect(await readEntitlement({ store, now: () => NOW }, OTHER)).toMatchObject({ tier: 'FREE', state: 'NONE' });
  });

  it('a pending purchase grants nothing and writes nothing', async () => {
    const store = memoryStore();
    const error = await apiError(run(fakePlay(subscription({ subscriptionState: 'SUBSCRIPTION_STATE_PENDING' })), store));
    expect([error.status, error.code, error.retryable]).toEqual([409, 'PURCHASE_PENDING', true]);
    expect(store.upsertPlay).not.toHaveBeenCalled();
  });

  it('a canceled pending purchase is invalid', async () => {
    const error = await apiError(run(fakePlay(subscription({ subscriptionState: 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED' }))));
    expect(error.code).toBe('PURCHASE_INVALID');
  });

  it('maps every Google state onto the stored entitlement and the response', async () => {
    const cases = [
      { google: 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', stored: 'GRACE', autoRenewing: true, tier: 'PRO', state: 'GRACE', expiry: NOW + DAY },
      // Canceled with paid time left keeps access until expiry (stored ACTIVE, auto_renewing=false).
      { google: 'SUBSCRIPTION_STATE_CANCELED', stored: 'ACTIVE', autoRenewing: false, tier: 'PRO', state: 'CANCELED', expiry: NOW + DAY },
      { google: 'SUBSCRIPTION_STATE_CANCELED', stored: 'ACTIVE', autoRenewing: false, tier: 'FREE', state: 'EXPIRED', expiry: NOW - DAY },
      { google: 'SUBSCRIPTION_STATE_PAUSED', stored: 'PAUSED', autoRenewing: true, tier: 'FREE', state: 'PAUSED', expiry: NOW + DAY },
      { google: 'SUBSCRIPTION_STATE_ON_HOLD', stored: 'ON_HOLD', autoRenewing: true, tier: 'FREE', state: 'PAUSED', expiry: NOW + DAY },
      { google: 'SUBSCRIPTION_STATE_EXPIRED', stored: 'EXPIRED', autoRenewing: false, tier: 'FREE', state: 'EXPIRED', expiry: NOW - DAY },
      // Google still says ACTIVE but the period ended: never Pro.
      { google: 'SUBSCRIPTION_STATE_ACTIVE', stored: 'ACTIVE', autoRenewing: true, tier: 'FREE', state: 'EXPIRED', expiry: NOW - 1 },
    ] as const;
    for (const c of cases) {
      const store = memoryStore();
      const autoRenew = c.google === 'SUBSCRIPTION_STATE_CANCELED' || c.google === 'SUBSCRIPTION_STATE_EXPIRED' ? false : true;
      const res = await run(fakePlay(subscription({
        subscriptionState: c.google,
        lineItems: [{ productId: 'gymlog_pro_monthly', expiryTime: iso(c.expiry), autoRenewingPlan: { autoRenewEnabled: autoRenew } }],
      })), store);
      expect(store.rows[0], c.google).toMatchObject({ state: c.stored, autoRenewing: c.autoRenewing, expiresAt: c.expiry });
      expect(res, `${c.google} ${c.expiry}`).toEqual({ tier: c.tier, productId: 'gymlog_pro_monthly', state: c.state, validUntil: c.expiry, serverTime: NOW });
    }
  });

  it('a later verify of a now-expired token downgrades the same row', async () => {
    const store = memoryStore();
    await run(fakePlay(subscription()), store);
    const res = await run(fakePlay(subscription({
      subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED',
      lineItems: [{ productId: 'gymlog_pro_monthly', expiryTime: iso(NOW - 1) }],
    })), store);
    expect(store.rows).toHaveLength(1);
    expect(res).toMatchObject({ tier: 'FREE', state: 'EXPIRED' });
  });

  it('a Google outage is a retryable 503 and writes nothing', async () => {
    const store = memoryStore();
    const error = await apiError(run(fakePlay(() => { throw new GooglePlayUnavailableError('http 503'); }), store));
    expect([error.status, error.code, error.retryable]).toEqual([503, 'BILLING_UNAVAILABLE', true]);
    expect(store.upsertPlay).not.toHaveBeenCalled();
  });

  it('an unknown Google state or a missing/invalid expiry is a retryable 503, never a grant', async () => {
    for (const sub of [
      subscription({ subscriptionState: 'SUBSCRIPTION_STATE_UNSPECIFIED' }),
      subscription({ subscriptionState: 'SUBSCRIPTION_STATE_FROM_THE_FUTURE' }),
      subscription({ lineItems: [{ productId: 'gymlog_pro_monthly' }] }),
      subscription({ lineItems: [{ productId: 'gymlog_pro_monthly', expiryTime: 'soon' }] }),
    ]) {
      const store = memoryStore();
      const error = await apiError(run(fakePlay(sub), store));
      expect([error.status, error.code]).toEqual([503, 'BILLING_UNAVAILABLE']);
      expect(store.upsertPlay).not.toHaveBeenCalled();
    }
  });

  it('missing Play configuration is a retryable 503 and calls nothing', async () => {
    const store = memoryStore();
    const error = await apiError(verifyPurchase({ play: null, store, now: () => NOW }, USER, req()));
    expect([error.status, error.code, error.retryable]).toEqual([503, 'BILLING_UNAVAILABLE', true]);
    expect(store.upsertPlay).not.toHaveBeenCalled();
  });

  it('never logs the purchase token, its hash or the account id on any path', async () => {
    const paths: Array<() => Promise<unknown>> = [
      () => run(fakePlay(subscription())),
      () => run(fakePlay(subscription({ acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING' }))),
      () => run(fakePlay(() => { throw new GooglePlayUnavailableError(`GET .../tokens/${TOKEN} failed`); })),
      () => run(fakePlay(subscription({ subscriptionState: 'SUBSCRIPTION_STATE_UNSPECIFIED' }))),
      () => run(fakePlay(subscription({}, OTHER))),
    ];
    const play = fakePlay(subscription({ acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING' }));
    play.client.acknowledgeSubscription.mockRejectedValue(new Error(`POST .../tokens/${TOKEN}:acknowledge 500`));
    paths.push(() => run(play));
    for (const p of paths) await p().catch(() => {});
    const logged = [errorSpy, warnSpy, logSpy].flatMap((s) => s.mock.calls).map((args) =>
      args.map((a: unknown) => (a instanceof Error ? `${a.message} ${a.stack}` : typeof a === 'string' ? a : JSON.stringify(a))).join(' '));
    expect(logged.length).toBeGreaterThan(0);
    for (const line of logged) {
      expect(line).not.toContain(TOKEN);
      expect(line).not.toContain(hashPurchaseToken(TOKEN));
      expect(line).not.toContain(obfuscatedAccountIdFor(USER));
    }
  });
});

describe('toEntitlementResponse / readEntitlement', () => {
  const row = (over: Partial<EntitlementRow>): EntitlementRow => ({
    userId: USER, productId: 'gymlog_pro_annual', purchaseTokenHash: 'h', state: 'ACTIVE', expiresAt: NOW + DAY,
    autoRenewing: true, lastVerifiedAt: NOW, source: 'play', ...over,
  });

  it('no row is FREE/NONE', () => {
    expect(toEntitlementResponse(null, NOW)).toEqual({ tier: 'FREE', productId: null, state: 'NONE', validUntil: null, serverTime: NOW });
  });

  it('projects stored states', () => {
    expect(toEntitlementResponse(row({}), NOW)).toMatchObject({ tier: 'PRO', state: 'ACTIVE', productId: 'gymlog_pro_annual' });
    expect(toEntitlementResponse(row({ state: 'GRACE' }), NOW)).toMatchObject({ tier: 'PRO', state: 'GRACE' });
    expect(toEntitlementResponse(row({ autoRenewing: false }), NOW)).toMatchObject({ tier: 'PRO', state: 'CANCELED' });
    expect(toEntitlementResponse(row({ state: 'CANCELED' }), NOW)).toMatchObject({ tier: 'FREE', state: 'CANCELED' });
    expect(toEntitlementResponse(row({ state: 'REVOKED' }), NOW)).toMatchObject({ tier: 'FREE', state: 'EXPIRED' });
    expect(toEntitlementResponse(row({ state: 'GRACE', expiresAt: NOW }), NOW)).toMatchObject({ tier: 'FREE', state: 'EXPIRED' });
  });

  it('readEntitlement answers for the caller only, with server time', async () => {
    const store = memoryStore();
    store.rows.push(row({ userId: OTHER }));
    expect(await readEntitlement({ store, now: () => NOW }, USER)).toEqual(toEntitlementResponse(null, NOW));
    expect(store.findBest).toHaveBeenCalledWith(USER, NOW);
  });
});

// ── Against the disposable Postgres (SYNC_TEST_DATABASE_URL): the real SQL store ──
const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('createDbEntitlementStore (integration)', () => {
  let pool: import('pg').Pool;
  let db: PoolDb;
  const users: string[] = [];
  const newUser = () => {
    const u = `test-billing-${crypto.randomUUID()}`;
    users.push(u);
    return u;
  };
  const write = (userId: string, over: Partial<PlayEntitlementWrite> = {}): PlayEntitlementWrite => ({
    userId, productId: 'gymlog_pro_monthly', purchaseTokenHash: hashPurchaseToken(`tok-${userId}`), state: 'ACTIVE',
    expiresAt: NOW + DAY, autoRenewing: true, verifiedAt: NOW, ...over,
  });

  beforeAll(async () => {
    const { drizzle } = await import('drizzle-orm/node-postgres');
    const { Pool } = await import('pg');
    const schema = await import('@/db/schema');
    pool = new Pool({ connectionString: url, max: 4 });
    db = drizzle(pool, { schema });
  });
  afterEach(() => vi.unstubAllEnvs());
  afterAll(async () => {
    if (!db) return;
    if (users.length) {
      const list = sql.join(users.map((u) => sql`${u}`), sql`, `);
      await db.execute(sql`DELETE FROM subscription_entitlements WHERE user_id IN (${list})`);
    }
    await pool.end();
  });

  const store = () => createDbEntitlementStore(() => db);

  it('upserts by (user, token hash) with source play and keeps one row on replay', async () => {
    const u = newUser();
    expect(await store().upsertPlay(write(u))).toBe('bound');
    expect(await store().upsertPlay(write(u, { state: 'GRACE', verifiedAt: NOW + 1 }))).toBe('bound');
    const rows = (await db.execute(sql`SELECT * FROM subscription_entitlements WHERE user_id = ${u}`)).rows;
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ state: 'GRACE', source: 'play', product_id: 'gymlog_pro_monthly' });
    expect(Number(rows[0].last_verified_at)).toBe(NOW + 1);
    expect(Number(rows[0].created_at)).toBe(NOW);
  });

  it('an older verification never overwrites a newer one', async () => {
    const u = newUser();
    await store().upsertPlay(write(u, { state: 'EXPIRED', verifiedAt: NOW + 10 }));
    await store().upsertPlay(write(u, { state: 'ACTIVE', verifiedAt: NOW }));
    expect((await store().findBest(u, NOW))?.state).toBe('EXPIRED');
  });

  it('refuses a token hash already bound to another user, also under concurrency', async () => {
    const a = newUser();
    const b = newUser();
    const hash = hashPurchaseToken(`shared-${a}`);
    expect(await store().upsertPlay(write(a, { purchaseTokenHash: hash }))).toBe('bound');
    expect(await store().upsertPlay(write(b, { purchaseTokenHash: hash }))).toBe('already_bound');

    const c = newUser();
    const d = newUser();
    const raced = hashPurchaseToken(`race-${c}`);
    const results = await Promise.all([
      store().upsertPlay(write(c, { purchaseTokenHash: raced })),
      store().upsertPlay(write(d, { purchaseTokenHash: raced })),
    ]);
    expect(results.sort()).toEqual(['already_bound', 'bound']);
    const owners = (await db.execute(sql`SELECT user_id FROM subscription_entitlements WHERE purchase_token_hash = ${raced}`)).rows;
    expect(owners).toHaveLength(1);
  });

  it('findBest prefers a granting row, then the latest expiry; test rows are inert in production', async () => {
    const u = newUser();
    await store().upsertPlay(write(u, { purchaseTokenHash: hashPurchaseToken(`${u}-1`), state: 'EXPIRED', expiresAt: NOW + 9 * DAY }));
    await store().upsertPlay(write(u, { purchaseTokenHash: hashPurchaseToken(`${u}-2`), state: 'ACTIVE', expiresAt: NOW + DAY, productId: 'gymlog_pro_annual' }));
    expect(await store().findBest(u, NOW)).toMatchObject({ state: 'ACTIVE', productId: 'gymlog_pro_annual', expiresAt: NOW + DAY, source: 'play' });
    expect((await store().findBest(u, NOW + 2 * DAY))?.state).toBe('EXPIRED');
    expect(await store().findBest(newUser(), NOW)).toBeNull();

    const t = newUser();
    await db.execute(sql`
      INSERT INTO subscription_entitlements
        (user_id, product_id, purchase_token_hash, state, expires_at, auto_renewing, last_verified_at, source, created_at, updated_at)
      VALUES (${t}, 'gymlog_pro_monthly', 'seeded', 'ACTIVE', ${NOW + DAY}, true, ${NOW}, 'test', ${NOW}, ${NOW})`);
    expect(await store().findBest(t, NOW)).not.toBeNull();
    vi.stubEnv('NODE_ENV', 'production');
    expect(await store().findBest(t, NOW)).toBeNull();
  });

  it('verifyPurchase end to end against the real store with a fake Google', async () => {
    const u = newUser();
    const token = `e2e.${u}`;
    const play = fakePlay(subscription({ acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING' }, u));
    const res = await verifyPurchase({ play, store: store(), now: () => NOW }, u, req({ purchaseToken: token }));
    expect(res).toMatchObject({ tier: 'PRO', state: 'ACTIVE' });
    const rows = (await db.execute(sql`SELECT purchase_token_hash FROM subscription_entitlements WHERE user_id = ${u}`)).rows;
    expect(rows).toEqual([{ purchase_token_hash: hashPurchaseToken(token) }]);
    const all = JSON.stringify((await db.execute(sql`SELECT * FROM subscription_entitlements WHERE user_id = ${u}`)).rows);
    expect(all).not.toContain(token);
    expect(play.client.acknowledgeSubscription).toHaveBeenCalledOnce();
  });
});
