// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { createHash } from 'node:crypto';
import {
  GooglePlayUnavailableError,
  createGooglePlayRestClient,
  obfuscatedAccountIdFor,
  readGooglePlayConfig,
} from './google-play';

const PACKAGE = 'com.chrisgoac.gymlog';
const TOKEN = 'tok.AO-J1Oz_secret-token';
const SA = JSON.stringify({ type: 'service_account', client_email: 'billing@proj.iam.gserviceaccount.com', private_key: '-----BEGIN PRIVATE KEY-----\nAAA\n-----END PRIVATE KEY-----\n' });

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

function client(fetchImpl: (url: string, init: RequestInit) => Promise<Response>) {
  const fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => fetchImpl(String(input), init ?? {}));
  return { fetchSpy, play: createGooglePlayRestClient({ getAccessToken: async () => 'access-1', fetch: fetchSpy }) };
}

const v2 = {
  kind: 'androidpublisher#subscriptionPurchaseV2',
  subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
  acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING',
  lineItems: [{ productId: 'gymlog_pro_monthly', expiryTime: '2026-11-11T10:00:00.123Z', autoRenewingPlan: { autoRenewEnabled: true } }],
  externalAccountIdentifiers: { obfuscatedExternalAccountId: 'abc' },
  regionCode: 'ES',
};

describe('obfuscatedAccountIdFor', () => {
  it('is the SHA-256 hex of the user id (64 chars, within Play’s obfuscatedAccountId limit)', () => {
    expect(obfuscatedAccountIdFor('user_1')).toBe(createHash('sha256').update('user_1').digest('hex'));
    expect(obfuscatedAccountIdFor('user_1')).toHaveLength(64);
  });
});

describe('readGooglePlayConfig', () => {
  it('needs both the package name and a parseable service account', () => {
    expect(readGooglePlayConfig({})).toBeNull();
    expect(readGooglePlayConfig({ GOOGLE_PLAY_PACKAGE_NAME: PACKAGE })).toBeNull();
    expect(readGooglePlayConfig({ GOOGLE_PLAY_SERVICE_ACCOUNT_JSON: SA })).toBeNull();
    expect(readGooglePlayConfig({ GOOGLE_PLAY_PACKAGE_NAME: PACKAGE, GOOGLE_PLAY_SERVICE_ACCOUNT_JSON: '{not json' })).toBeNull();
    expect(readGooglePlayConfig({ GOOGLE_PLAY_PACKAGE_NAME: PACKAGE, GOOGLE_PLAY_SERVICE_ACCOUNT_JSON: '{"client_email":"x"}' })).toBeNull();
    expect(readGooglePlayConfig({ GOOGLE_PLAY_PACKAGE_NAME: ` ${PACKAGE} `, GOOGLE_PLAY_SERVICE_ACCOUNT_JSON: SA })).toEqual({
      packageName: PACKAGE,
      serviceAccount: { clientEmail: 'billing@proj.iam.gserviceaccount.com', privateKey: expect.stringContaining('PRIVATE KEY') },
    });
  });
});

describe('createGooglePlayRestClient (fake fetch, no network)', () => {
  it('GETs subscriptionsv2 for the package with a bearer token and parses the narrow shape', async () => {
    const { play, fetchSpy } = client(async () => json(200, v2));
    const sub = await play.getSubscription(PACKAGE, TOKEN);
    expect(sub).toEqual({
      subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
      acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING',
      lineItems: [{ productId: 'gymlog_pro_monthly', expiryTime: '2026-11-11T10:00:00.123Z', autoRenewingPlan: { autoRenewEnabled: true } }],
      externalAccountIdentifiers: { obfuscatedExternalAccountId: 'abc' },
    });
    const [url, init] = fetchSpy.mock.calls[0];
    expect(String(url)).toBe(
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}/purchases/subscriptionsv2/tokens/${encodeURIComponent(TOKEN)}`);
    expect(new Headers(init?.headers).get('authorization')).toBe('Bearer access-1');
    expect(init?.signal).toBeInstanceOf(AbortSignal);
  });

  it('an unknown/invalid/expired-long-ago token is null (not an outage)', async () => {
    for (const status of [400, 404, 410]) {
      const { play } = client(async () => json(status, { error: { code: status } }));
      expect(await play.getSubscription(PACKAGE, TOKEN), String(status)).toBeNull();
    }
  });

  it('auth failures, throttling, 5xx, network errors and malformed bodies are outages whose message omits the token', async () => {
    const failures: Array<() => Promise<Response>> = [
      async () => json(401, {}),
      async () => json(403, {}),
      async () => json(429, {}),
      async () => json(503, {}),
      async () => { throw new TypeError(`fetch failed for ${TOKEN}`); },
      async () => new Response('<html>', { status: 200 }),
      async () => json(200, { subscriptionState: 7 }),
    ];
    for (const f of failures) {
      const { play } = client(f);
      const error = await play.getSubscription(PACKAGE, TOKEN).then(() => null, (e: unknown) => e);
      expect(error).toBeInstanceOf(GooglePlayUnavailableError);
      expect(`${(error as Error).message} ${(error as Error).stack}`).not.toContain(TOKEN);
      expect((error as Error).cause).toBeUndefined();
    }
  });

  it('a failing access-token exchange is an outage', async () => {
    const play = createGooglePlayRestClient({ getAccessToken: async () => { throw new Error('invalid_grant'); }, fetch: vi.fn() });
    await expect(play.getSubscription(PACKAGE, TOKEN)).rejects.toBeInstanceOf(GooglePlayUnavailableError);
  });

  it('acknowledges via the subscriptions acknowledge endpoint', async () => {
    const { play, fetchSpy } = client(async () => new Response(null, { status: 204 }));
    await play.acknowledgeSubscription(PACKAGE, 'gymlog_pro_annual', TOKEN);
    const [url, init] = fetchSpy.mock.calls[0];
    expect(String(url)).toBe(
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}/purchases/subscriptions/gymlog_pro_annual/tokens/${encodeURIComponent(TOKEN)}:acknowledge`);
    expect(init?.method).toBe('POST');

    const failing = client(async () => json(500, {}));
    const error = await failing.play.acknowledgeSubscription(PACKAGE, 'gymlog_pro_annual', TOKEN).then(() => null, (e: unknown) => e);
    expect(error).toBeInstanceOf(GooglePlayUnavailableError);
    expect((error as Error).message).not.toContain(TOKEN);
  });
});
