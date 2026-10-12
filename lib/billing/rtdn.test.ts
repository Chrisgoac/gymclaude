// @vitest-environment node
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { generateKeyPairSync, createSign } from 'node:crypto';
import { sql } from 'drizzle-orm';
import { OAuth2Client } from 'google-auth-library';
import type { PoolDb } from '@/db/pool';
import {
  GooglePlayUnavailableError,
  obfuscatedAccountIdFor,
  type GooglePlay,
  type GooglePlayClient,
  type GooglePlaySubscription,
} from './google-play';
import { createDbEntitlementStore, hashPurchaseToken, type EntitlementRow } from './entitlements';
import {
  PubSubPushSchema,
  RtdnAuthUnavailableError,
  createDbRtdnStore,
  createGoogleOidcVerifier,
  decodeNotification,
  processPushMessage,
  readRtdnConfig,
  type RtdnCommit,
  type RtdnDeps,
  type RtdnEvent,
  type RtdnStore,
} from './rtdn';

const NOW = 1_767_225_600_000;
const DAY = 86_400_000;
const PACKAGE = 'com.chrisgoac.gymlog';
const USER = 'user_rtdn_1';
const TOKEN = 'rtdn.AO-J1Oz_secret-token';
const OLD_TOKEN = 'rtdn.AO-J1Oz_old-secret-token';

// ── Builders ──

function notification(body: Record<string, unknown>, over: Record<string, unknown> = {}) {
  return { version: '1.0', packageName: PACKAGE, eventTimeMillis: String(NOW - 1000), ...over, ...body };
}
const subNotif = (notificationType: number, purchaseToken = TOKEN) =>
  notification({ subscriptionNotification: { version: '1.0', notificationType, purchaseToken, subscriptionId: 'gymlog_pro_monthly' } });
const voidedNotif = (purchaseToken = TOKEN, productType = 1) =>
  notification({ voidedPurchaseNotification: { purchaseToken, orderId: 'GPA.1', productType, refundType: 1 } });

function envelope(data: unknown, messageId = `m-${crypto.randomUUID()}`) {
  const raw = typeof data === 'string' ? data : Buffer.from(JSON.stringify(data)).toString('base64');
  return PubSubPushSchema.parse({
    message: { data: raw, messageId, publishTime: new Date(NOW - 500).toISOString() },
    subscription: 'projects/p/subscriptions/rtdn',
  });
}

const subscription = (over: Partial<GooglePlaySubscription> = {}, userId = USER): GooglePlaySubscription => ({
  subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
  acknowledgementState: 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
  lineItems: [{ productId: 'gymlog_pro_monthly', expiryTime: new Date(NOW + 30 * DAY).toISOString(), autoRenewingPlan: { autoRenewEnabled: true } }],
  externalAccountIdentifiers: { obfuscatedExternalAccountId: obfuscatedAccountIdFor(userId) },
  ...over,
});

function fakePlay(byToken: Record<string, GooglePlaySubscription | null | Error>): GooglePlay & { client: { getSubscription: ReturnType<typeof vi.fn> } } {
  const client: GooglePlayClient = {
    getSubscription: vi.fn(async (_pkg: string, token: string) => {
      const value = byToken[token];
      if (value instanceof Error) throw value;
      return value ?? null;
    }),
    acknowledgeSubscription: vi.fn(async () => {}),
  };
  return { packageName: PACKAGE, client } as GooglePlay & { client: { getSubscription: ReturnType<typeof vi.fn> } };
}

/** In-memory store with the same semantics as the SQL one (stale guard, one tx per commit). */
function memoryStore(rows: EntitlementRow[] = []) {
  const events = new Map<string, RtdnEvent & { outcome: string | null; processedAt: number | null }>();
  const store: RtdnStore & { events: typeof events; rows: EntitlementRow[]; failCommit?: boolean } = {
    events,
    rows,
    async isProcessed(messageId) {
      return (events.get(messageId)?.processedAt ?? null) !== null;
    },
    async recordReceived(event) {
      if (!events.has(event.messageId)) events.set(event.messageId, { ...event, outcome: null, processedAt: null });
    },
    async findByTokenHash(hash) {
      const row = rows.find((r) => r.purchaseTokenHash === hash);
      return row ? { userId: row.userId, productId: row.productId, expiresAt: row.expiresAt } : null;
    },
    async commit(c: RtdnCommit) {
      if (store.failCommit) throw new Error('db down');
      if ((events.get(c.event.messageId)?.processedAt ?? null) !== null) return 'duplicate';
      for (const w of c.upserts) {
        const i = rows.findIndex((r) => r.userId === w.userId && r.purchaseTokenHash === w.purchaseTokenHash);
        const next: EntitlementRow = { userId: w.userId, productId: w.productId, purchaseTokenHash: w.purchaseTokenHash, state: w.state,
          expiresAt: w.expiresAt, autoRenewing: w.autoRenewing, lastVerifiedAt: w.verifiedAt, source: 'play' };
        if (i < 0) rows.push(next);
        else if (rows[i].lastVerifiedAt <= w.verifiedAt) rows[i] = next;
      }
      if (c.supersede) {
        const r = rows.find((x) => x.userId === c.supersede!.userId && x.purchaseTokenHash === c.supersede!.purchaseTokenHash);
        if (r && r.lastVerifiedAt <= c.supersede.at) Object.assign(r, { state: 'EXPIRED', autoRenewing: false, lastVerifiedAt: c.supersede.at });
      }
      events.set(c.event.messageId, { ...c.event, outcome: c.outcome, processedAt: c.processedAt });
      return 'committed';
    },
  };
  return store;
}

const row = (over: Partial<EntitlementRow> = {}): EntitlementRow => ({
  userId: USER, productId: 'gymlog_pro_monthly', purchaseTokenHash: hashPurchaseToken(TOKEN), state: 'ACTIVE',
  expiresAt: NOW + 10 * DAY, autoRenewing: true, lastVerifiedAt: NOW - DAY, source: 'play', ...over,
});

let clock = NOW;
const deps = (play: GooglePlay, store: RtdnStore): RtdnDeps => ({ play, store, now: () => clock });

let logs: MockInstance[];
beforeEach(() => {
  clock = NOW;
  logs = [vi.spyOn(console, 'info').mockImplementation(() => {}), vi.spyOn(console, 'warn').mockImplementation(() => {}),
    vi.spyOn(console, 'error').mockImplementation(() => {}), vi.spyOn(console, 'log').mockImplementation(() => {})];
});
afterEach(() => {
  const printed = logs.flatMap((s) => s.mock.calls.map((c) => JSON.stringify(c))).join('\n');
  // Logs never carry a raw purchase token.
  expect(printed).not.toContain(TOKEN);
  expect(printed).not.toContain(OLD_TOKEN);
  vi.restoreAllMocks();
});

// ── Decoding ──

describe('decodeNotification', () => {
  const b64 = (v: unknown) => Buffer.from(typeof v === 'string' ? v : JSON.stringify(v)).toString('base64');

  it('decodes subscription, voided and test notifications', () => {
    expect(decodeNotification(b64(subNotif(4)))).toMatchObject({ kind: 'subscription', notificationType: 4, purchaseToken: TOKEN, packageName: PACKAGE, eventTime: NOW - 1000 });
    expect(decodeNotification(b64(voidedNotif()))).toMatchObject({ kind: 'voided', purchaseToken: TOKEN, productType: 1 });
    expect(decodeNotification(b64(notification({ testNotification: { version: '1.0' } })))).toMatchObject({ kind: 'test' });
    expect(decodeNotification(b64(notification({ oneTimeProductNotification: { notificationType: 1 } })))).toMatchObject({ kind: 'unknown' });
  });

  it('returns null for malformed data', () => {
    expect(decodeNotification('%%%not-base64%%%')).toBeNull();
    expect(decodeNotification(b64('not json'))).toBeNull();
    expect(decodeNotification(b64({ packageName: PACKAGE }))).toBeNull(); // no eventTimeMillis
    expect(decodeNotification(b64(notification({ subscriptionNotification: { notificationType: 'x' } })))).toBeNull();
    expect(decodeNotification(b64(notification({ subscriptionNotification: { notificationType: 2, purchaseToken: 'bad token!' } })))).toBeNull();
  });
});

// ── Processing ──

describe('processPushMessage', () => {
  it('renewal refetches Google and updates the owner row', async () => {
    const store = memoryStore([row()]);
    const play = fakePlay({ [TOKEN]: subscription({ lineItems: [{ productId: 'gymlog_pro_monthly', expiryTime: new Date(NOW + 40 * DAY).toISOString(), autoRenewingPlan: { autoRenewEnabled: true } }] }) });
    const env = envelope(subNotif(2));
    expect(await processPushMessage(deps(play, store), env)).toEqual({ status: 200, outcome: 'updated' });
    expect(store.rows[0]).toMatchObject({ state: 'ACTIVE', expiresAt: NOW + 40 * DAY, lastVerifiedAt: NOW });
    expect(play.client.getSubscription).toHaveBeenCalledWith(PACKAGE, TOKEN);
    expect(store.events.get(env.message.messageId)).toMatchObject({ outcome: 'updated', processedAt: NOW, purchaseTokenHash: hashPurchaseToken(TOKEN), notificationKind: 'subscription', notificationType: 2 });
  });

  it('a duplicate message is acknowledged without reprocessing', async () => {
    const store = memoryStore([row()]);
    const play = fakePlay({ [TOKEN]: subscription() });
    const env = envelope(subNotif(2), 'dup-1');
    await processPushMessage(deps(play, store), env);
    expect(await processPushMessage(deps(play, store), env)).toEqual({ status: 200, outcome: 'duplicate' });
    expect(play.client.getSubscription).toHaveBeenCalledTimes(1);
  });

  it('out-of-order events: Google state at processing time wins, whatever the notification says', async () => {
    // A late RENEWED (2) arrives after CANCELED (3) and EXPIRED (13): Google says EXPIRED, so EXPIRED is stored.
    const store = memoryStore([row()]);
    const expired = subscription({ subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED', lineItems: [{ productId: 'gymlog_pro_monthly', expiryTime: new Date(NOW - DAY).toISOString() }] });
    const play = fakePlay({ [TOKEN]: expired });
    await processPushMessage(deps(play, store), envelope(subNotif(13)));
    clock = NOW + 1;
    await processPushMessage(deps(play, store), envelope(subNotif(3)));
    clock = NOW + 2;
    expect(await processPushMessage(deps(play, store), envelope(subNotif(2)))).toEqual({ status: 200, outcome: 'updated' });
    expect(store.rows[0]).toMatchObject({ state: 'EXPIRED', expiresAt: NOW - DAY });
  });

  it('cancel keeps access until expiry (ACTIVE, no auto-renew); grace keeps Pro', async () => {
    const store = memoryStore([row()]);
    await processPushMessage(deps(fakePlay({ [TOKEN]: subscription({ subscriptionState: 'SUBSCRIPTION_STATE_CANCELED' }) }), store), envelope(subNotif(3)));
    expect(store.rows[0]).toMatchObject({ state: 'ACTIVE', autoRenewing: false });
    clock = NOW + 1;
    await processPushMessage(deps(fakePlay({ [TOKEN]: subscription({ subscriptionState: 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD' }) }), store), envelope(subNotif(6)));
    expect(store.rows[0]).toMatchObject({ state: 'GRACE' });
  });

  it('a stale write (older fetch) never overwrites a newer one', async () => {
    const store = memoryStore([row({ state: 'EXPIRED', lastVerifiedAt: NOW + 10 })]);
    await processPushMessage(deps(fakePlay({ [TOKEN]: subscription() }), store), envelope(subNotif(2)));
    expect(store.rows[0].state).toBe('EXPIRED');
  });

  it('refund (voided) revokes when Google no longer entitles the purchase', async () => {
    const store = memoryStore([row()]);
    const play = fakePlay({ [TOKEN]: subscription({ subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED', lineItems: [{ productId: 'gymlog_pro_monthly', expiryTime: new Date(NOW - 1).toISOString() }] }) });
    expect(await processPushMessage(deps(play, store), envelope(voidedNotif()))).toEqual({ status: 200, outcome: 'revoked' });
    expect(store.rows[0]).toMatchObject({ state: 'REVOKED', autoRenewing: false, expiresAt: NOW });
  });

  it('voided with the token unknown to Google also revokes; voided one-time products are ignored', async () => {
    const store = memoryStore([row()]);
    expect(await processPushMessage(deps(fakePlay({}), store), envelope(voidedNotif()))).toEqual({ status: 200, outcome: 'revoked' });
    expect(store.rows[0].state).toBe('REVOKED');
    const other = memoryStore([row()]);
    expect(await processPushMessage(deps(fakePlay({}), other), envelope(voidedNotif(TOKEN, 2)))).toEqual({ status: 200, outcome: 'ignored_type' });
    expect(other.rows[0].state).toBe('ACTIVE');
  });

  it('voided while Google still entitles the purchase (partial refund) follows Google', async () => {
    const store = memoryStore([row()]);
    expect(await processPushMessage(deps(fakePlay({ [TOKEN]: subscription() }), store), envelope(voidedNotif()))).toEqual({ status: 200, outcome: 'updated' });
    expect(store.rows[0].state).toBe('ACTIVE');
  });

  it('unknown token: event recorded, acknowledged, nothing granted', async () => {
    const store = memoryStore();
    const env = envelope(subNotif(4));
    expect(await processPushMessage(deps(fakePlay({ [TOKEN]: subscription() }), store), env)).toEqual({ status: 200, outcome: 'unknown_token' });
    expect(store.rows).toHaveLength(0);
    expect(store.events.get(env.message.messageId)?.processedAt).toBe(NOW);
  });

  it('token Google does not know for our package: recorded, no change', async () => {
    const store = memoryStore([row()]);
    expect(await processPushMessage(deps(fakePlay({}), store), envelope(subNotif(2)))).toEqual({ status: 200, outcome: 'token_unknown_to_google' });
    expect(store.rows[0].state).toBe('ACTIVE');
  });

  it('transient Google error: 503, event left unprocessed, a retry processes it', async () => {
    const store = memoryStore([row()]);
    const env = envelope(subNotif(2), 'retry-1');
    const failing = fakePlay({ [TOKEN]: new GooglePlayUnavailableError('subscriptionsv2.get: http 503') });
    expect(await processPushMessage(deps(failing, store), env)).toEqual({ status: 503, outcome: 'google_unavailable' });
    expect(store.events.get('retry-1')).toMatchObject({ processedAt: null });
    expect(await processPushMessage(deps(fakePlay({ [TOKEN]: subscription() }), store), env)).toEqual({ status: 200, outcome: 'updated' });
  });

  it('store failure: 503 so Pub/Sub retries', async () => {
    const store = memoryStore([row()]);
    store.failCommit = true;
    expect(await processPushMessage(deps(fakePlay({ [TOKEN]: subscription() }), store), envelope(subNotif(2)))).toEqual({ status: 503, outcome: 'store_unavailable' });
  });

  it('upgrade/downgrade: linkedPurchaseToken moves the user to the new token and expires the old one', async () => {
    const store = memoryStore([row({ purchaseTokenHash: hashPurchaseToken(OLD_TOKEN) })]);
    const upgraded = subscription({
      linkedPurchaseToken: OLD_TOKEN,
      lineItems: [{ productId: 'gymlog_pro_annual', expiryTime: new Date(NOW + 365 * DAY).toISOString(), autoRenewingPlan: { autoRenewEnabled: true } }],
    });
    expect(await processPushMessage(deps(fakePlay({ [TOKEN]: upgraded }), store), envelope(subNotif(4)))).toEqual({ status: 200, outcome: 'updated' });
    const byHash = (t: string) => store.rows.find((r) => r.purchaseTokenHash === hashPurchaseToken(t));
    expect(byHash(TOKEN)).toMatchObject({ userId: USER, productId: 'gymlog_pro_annual', state: 'ACTIVE' });
    expect(byHash(OLD_TOKEN)).toMatchObject({ userId: USER, state: 'EXPIRED', autoRenewing: false });
  });

  it('never grants when Google binds the purchase to another account', async () => {
    const store = memoryStore([row({ purchaseTokenHash: hashPurchaseToken(OLD_TOKEN) })]);
    const foreign = subscription({ linkedPurchaseToken: OLD_TOKEN }, 'someone_else');
    expect(await processPushMessage(deps(fakePlay({ [TOKEN]: foreign }), store), envelope(subNotif(4)))).toEqual({ status: 200, outcome: 'account_mismatch' });
    expect(store.rows).toHaveLength(1);
    expect(store.rows[0].state).toBe('ACTIVE');
  });

  it('pending, unknown products and unknown states never grant', async () => {
    const store = memoryStore([row()]);
    const run = (s: GooglePlaySubscription) => processPushMessage(deps(fakePlay({ [TOKEN]: s }), store), envelope(subNotif(2)));
    expect((await run(subscription({ subscriptionState: 'SUBSCRIPTION_STATE_PENDING' }))).outcome).toBe('pending');
    expect((await run(subscription({ lineItems: [{ productId: 'other_app_product', expiryTime: new Date(NOW + DAY).toISOString() }] }))).outcome).toBe('invalid_product');
    expect((await run(subscription({ subscriptionState: 'SUBSCRIPTION_STATE_FUTURE' }))).outcome).toBe('unexpected_state');
    expect(store.rows[0]).toMatchObject({ state: 'ACTIVE', lastVerifiedAt: NOW - DAY });
  });

  it('test notifications, unknown types, malformed data and other packages are recorded and acknowledged', async () => {
    const store = memoryStore();
    const play = fakePlay({});
    const cases: Array<[unknown, string]> = [
      [notification({ testNotification: { version: '1.0' } }), 'test'],
      [notification({ oneTimeProductNotification: { notificationType: 1 } }), 'ignored_type'],
      ['!!!', 'malformed'],
      [subNotif(2), 'wrong_package'],
    ];
    for (const [data, outcome] of cases) {
      const d = outcome === 'wrong_package' ? { ...(data as object), packageName: 'com.evil.app' } : data;
      const env = envelope(d);
      expect(await processPushMessage(deps(play, store), env)).toEqual({ status: 200, outcome });
      expect(store.events.get(env.message.messageId)).toMatchObject({ outcome, processedAt: NOW });
    }
    expect(play.client.getSubscription).not.toHaveBeenCalled();
  });

  it('logs only message id, type, hash prefix and outcome', async () => {
    const store = memoryStore([row()]);
    await processPushMessage(deps(fakePlay({ [TOKEN]: subscription() }), store), envelope(subNotif(2), 'log-1'));
    const line = (console.info as unknown as MockInstance).mock.calls.at(-1)?.join(' ') ?? '';
    expect(line).toContain('log-1');
    expect(line).toContain('updated');
    expect(line).not.toContain(hashPurchaseToken(TOKEN)); // only a short prefix
  });

  it('logs an outcome that makes Pub/Sub retry as a warning', async () => {
    const store = memoryStore([row()]);
    store.failCommit = true;
    await processPushMessage(deps(fakePlay({ [TOKEN]: subscription() }), store), envelope(subNotif(2), 'warn-1'));
    const line = (console.warn as unknown as MockInstance).mock.calls.at(-1)?.join(' ') ?? '';
    expect(line).toContain('warn-1');
    expect(line).toContain('store_unavailable');
  });
});

// ── Config + OIDC verifier ──

describe('readRtdnConfig', () => {
  it('needs both the audience and the push service account', () => {
    expect(readRtdnConfig({ GOOGLE_PLAY_RTDN_AUDIENCE: 'https://gym.example/api/webhooks/google-play', GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT: 'push@p.iam.gserviceaccount.com' }))
      .toEqual({ audience: 'https://gym.example/api/webhooks/google-play', serviceAccountEmail: 'push@p.iam.gserviceaccount.com' });
    expect(readRtdnConfig({ GOOGLE_PLAY_RTDN_AUDIENCE: 'a' })).toBeNull();
    expect(readRtdnConfig({ GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT: 'b' })).toBeNull();
  });
});

describe('createGoogleOidcVerifier (real signature checks, no network)', () => {
  const AUD = 'https://gym.example/api/webhooks/google-play';
  const SA = 'push@p.iam.gserviceaccount.com';
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const forger = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const pem = publicKey.export({ type: 'spki', format: 'pem' }).toString();

  const b64url = (v: unknown) => Buffer.from(JSON.stringify(v)).toString('base64url');
  function jwt(claims: Record<string, unknown> = {}, key = privateKey, kid = 'k1') {
    const iat = Math.floor(Date.now() / 1000);
    const body = { iss: 'https://accounts.google.com', aud: AUD, email: SA, email_verified: true, sub: '1', iat, exp: iat + 3600, ...claims };
    const signed = `${b64url({ alg: 'RS256', typ: 'JWT', kid })}.${b64url(body)}`;
    const signature = createSign('RSA-SHA256').update(signed).sign(key).toString('base64url');
    return `${signed}.${signature}`;
  }
  class CertsClient extends OAuth2Client {
    fail = false;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    override async getFederatedSignonCertsAsync(): Promise<any> {
      if (this.fail) throw new Error('jwks fetch failed');
      return { certs: { k1: pem }, format: 'PEM' };
    }
  }
  const verifier = (client = new CertsClient()) => createGoogleOidcVerifier({ audience: AUD, serviceAccountEmail: SA }, client);

  it('accepts a Google-signed token for our audience and push account', async () => {
    expect(await verifier().verify(`Bearer ${jwt()}`)).toBe('ok');
  });

  it('rejects missing, forged, foreign-audience, other-account, unverified-email, other-issuer and expired tokens', async () => {
    const v = verifier();
    expect(await v.verify(null)).toBe('invalid');
    expect(await v.verify('Basic abc')).toBe('invalid');
    expect(await v.verify(`Bearer ${jwt({}, forger.privateKey)}`)).toBe('invalid');
    expect(await v.verify(`Bearer ${jwt({}, privateKey, 'unknown-kid')}`)).toBe('invalid');
    expect(await v.verify(`Bearer ${jwt({ aud: 'https://other.example' })}`)).toBe('invalid');
    expect(await v.verify(`Bearer ${jwt({ email: 'attacker@p.iam.gserviceaccount.com' })}`)).toBe('invalid');
    expect(await v.verify(`Bearer ${jwt({ email_verified: false })}`)).toBe('invalid');
    expect(await v.verify(`Bearer ${jwt({ iss: 'https://evil.example' })}`)).toBe('invalid');
    const past = Math.floor(Date.now() / 1000) - 7200;
    expect(await v.verify(`Bearer ${jwt({ iat: past, exp: past + 600 })}`)).toBe('invalid');
  });

  it('JWKS outage is transient, not a rejection', async () => {
    const client = new CertsClient();
    client.fail = true;
    await expect(verifier(client).verify(`Bearer ${jwt()}`)).rejects.toBeInstanceOf(RtdnAuthUnavailableError);
  });
});

// ── Against the disposable Postgres (SYNC_TEST_DATABASE_URL): the real SQL store ──
const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('createDbRtdnStore (integration)', () => {
  let pool: import('pg').Pool;
  let db: PoolDb;
  const users: string[] = [];
  const messages: string[] = [];
  const newUser = () => {
    const u = `test-rtdn-${crypto.randomUUID()}`;
    users.push(u);
    return u;
  };
  const msg = () => {
    const m = `test-rtdn-msg-${crypto.randomUUID()}`;
    messages.push(m);
    return m;
  };

  beforeAll(async () => {
    const { drizzle } = await import('drizzle-orm/node-postgres');
    const { Pool } = await import('pg');
    const schema = await import('@/db/schema');
    pool = new Pool({ connectionString: url, max: 4 });
    db = drizzle(pool, { schema });
  });
  afterAll(async () => {
    if (!db) return;
    if (users.length) {
      const list = sql.join(users.map((u) => sql`${u}`), sql`, `);
      await db.execute(sql`DELETE FROM subscription_entitlements WHERE user_id IN (${list})`);
    }
    if (messages.length) {
      const list = sql.join(messages.map((m) => sql`${m}`), sql`, `);
      await db.execute(sql`DELETE FROM billing_events WHERE message_id IN (${list})`);
    }
    await pool.end();
  });

  const rtdnStore = () => createDbRtdnStore(() => db);
  const entStore = () => createDbEntitlementStore(() => db);
  const event = (messageId: string, tokenHash: string | null = null): RtdnEvent => ({
    messageId, eventTime: NOW, notificationKind: 'subscription', notificationType: 2, purchaseTokenHash: tokenHash, receivedAt: NOW,
  });

  it('end to end: renewal, duplicate, upgrade with linked token, voided', async () => {
    const u = newUser();
    const oldToken = `old.${u}`;
    const newToken = `new.${u}`;
    await entStore().upsertPlay({ userId: u, productId: 'gymlog_pro_monthly', purchaseTokenHash: hashPurchaseToken(oldToken),
      state: 'ACTIVE', expiresAt: NOW + DAY, autoRenewing: true, verifiedAt: NOW - DAY });

    const store = rtdnStore();
    const renewed = fakePlay({ [oldToken]: subscription({ lineItems: [{ productId: 'gymlog_pro_monthly', expiryTime: new Date(NOW + 31 * DAY).toISOString(), autoRenewingPlan: { autoRenewEnabled: true } }] }, u) });
    const m1 = msg();
    expect(await processPushMessage(deps(renewed, store), envelope(subNotif(2, oldToken), m1))).toEqual({ status: 200, outcome: 'updated' });
    expect(await processPushMessage(deps(renewed, store), envelope(subNotif(2, oldToken), m1))).toEqual({ status: 200, outcome: 'duplicate' });
    expect((await entStore().findBest(u, NOW))?.expiresAt).toBe(NOW + 31 * DAY);

    clock = NOW + 1;
    const upgraded = fakePlay({ [newToken]: subscription({ linkedPurchaseToken: oldToken,
      lineItems: [{ productId: 'gymlog_pro_annual', expiryTime: new Date(NOW + 365 * DAY).toISOString(), autoRenewingPlan: { autoRenewEnabled: true } }] }, u) });
    expect(await processPushMessage(deps(upgraded, store), envelope(subNotif(4, newToken), msg()))).toEqual({ status: 200, outcome: 'updated' });
    const rows = (await db.execute(sql`SELECT purchase_token_hash, state, product_id FROM subscription_entitlements WHERE user_id = ${u} ORDER BY product_id`)).rows;
    expect(rows).toEqual([
      { purchase_token_hash: hashPurchaseToken(newToken), state: 'ACTIVE', product_id: 'gymlog_pro_annual' },
      { purchase_token_hash: hashPurchaseToken(oldToken), state: 'EXPIRED', product_id: 'gymlog_pro_monthly' },
    ]);

    clock = NOW + 2;
    expect(await processPushMessage(deps(fakePlay({}), store), envelope(voidedNotif(newToken), msg()))).toEqual({ status: 200, outcome: 'revoked' });
    expect((await entStore().findBest(u, NOW + 2))?.state).not.toBe('ACTIVE');

    const stored = JSON.stringify((await db.execute(sql`SELECT * FROM billing_events WHERE message_id = ${m1}`)).rows);
    expect(stored).not.toContain(oldToken);
    expect(stored).toContain(hashPurchaseToken(oldToken));
  });

  it('unknown token is recorded as processed; transient failure leaves it unprocessed', async () => {
    const store = rtdnStore();
    const m = msg();
    const failing = fakePlay({ [`ghost.${m}`]: new GooglePlayUnavailableError('x') });
    expect((await processPushMessage(deps(failing, store), envelope(subNotif(4, `ghost.${m}`), m))).status).toBe(503);
    expect(await store.isProcessed(m)).toBe(false);
    const ok = fakePlay({ [`ghost.${m}`]: subscription() });
    expect(await processPushMessage(deps(ok, store), envelope(subNotif(4, `ghost.${m}`), m))).toEqual({ status: 200, outcome: 'unknown_token' });
    const r = (await db.execute(sql`SELECT outcome, processed_at FROM billing_events WHERE message_id = ${m}`)).rows[0];
    expect(r).toMatchObject({ outcome: 'unknown_token' });
    expect(Number(r.processed_at)).toBe(NOW);
  });

  it('event and entitlement writes are atomic: a failing write rolls back both', async () => {
    const u = newUser();
    const m = msg();
    const hash = hashPurchaseToken(`atomic.${u}`);
    const good = { userId: u, productId: 'gymlog_pro_monthly', purchaseTokenHash: hash, state: 'ACTIVE' as const,
      expiresAt: NOW + DAY, autoRenewing: true, verifiedAt: NOW };
    const bad = { ...good, purchaseTokenHash: `${hash}-2`, state: 'BOGUS' as unknown as 'ACTIVE' }; // violates the CHECK
    await expect(rtdnStore().commit({ event: event(m, hash), upserts: [good, bad], outcome: 'updated', processedAt: NOW })).rejects.toThrow();
    expect((await db.execute(sql`SELECT 1 FROM subscription_entitlements WHERE user_id = ${u}`)).rows).toHaveLength(0);
    expect((await db.execute(sql`SELECT 1 FROM billing_events WHERE message_id = ${m}`)).rows).toHaveLength(0);
  });

  it('concurrent deliveries of one message commit exactly once', async () => {
    const m = msg();
    const results = await Promise.all([1, 2, 3].map(() =>
      rtdnStore().commit({ event: event(m), upserts: [], outcome: 'test', processedAt: NOW })));
    expect(results.sort()).toEqual(['committed', 'duplicate', 'duplicate']);
  });
});
