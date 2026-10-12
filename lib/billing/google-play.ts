import { createHash } from 'node:crypto';
import { JWT } from 'google-auth-library';
import { z } from 'zod';

/**
 * Narrow Google Play Developer API adapter (androidpublisher v3): only the two calls GymLog needs,
 * `purchases.subscriptionsv2.get` and `purchases.subscriptions.acknowledge`, over plain `fetch`.
 * Auth is a service-account JWT exchanged for an OAuth access token by `google-auth-library`
 * (official, token caching/refresh included) — the full `googleapis` client (~240 MB unpacked,
 * every Google API) is not needed for two REST calls.
 *
 * Purchase tokens are secrets: they only travel in the request URL to Google and never appear in
 * error messages, logs or the database (see `hashPurchaseToken`).
 */

export const GOOGLE_PLAY_PRODUCT_IDS = ['gymlog_pro_monthly', 'gymlog_pro_annual'] as const;
export type GooglePlayProductId = (typeof GOOGLE_PLAY_PRODUCT_IDS)[number];

const ANDROID_PUBLISHER_SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
const API_BASE = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications';
const DEFAULT_TIMEOUT_MS = 10_000;

/**
 * The Android client must pass this as `BillingFlowParams.setObfuscatedAccountId(...)`; Google echoes
 * it in `externalAccountIdentifiers.obfuscatedExternalAccountId` and the server requires the match,
 * binding the purchase to the signed-in account. SHA-256 hex = 64 chars (Play's limit) and no PII.
 */
export function obfuscatedAccountIdFor(userId: string): string {
  return createHash('sha256').update(userId).digest('hex');
}

/** The subset of `SubscriptionPurchaseV2` the server reads. Unknown fields are dropped. */
const SubscriptionSchema = z.object({
  subscriptionState: z.string(),
  /** Set on an upgrade/downgrade/resubscribe: the token this purchase replaces. */
  linkedPurchaseToken: z.string().optional(),
  acknowledgementState: z.string().optional(),
  lineItems: z.array(z.object({
    productId: z.string(),
    expiryTime: z.string().optional(),
    autoRenewingPlan: z.object({ autoRenewEnabled: z.boolean().optional() }).optional(),
  })).default([]),
  externalAccountIdentifiers: z.object({ obfuscatedExternalAccountId: z.string().optional() }).optional(),
});
export type GooglePlaySubscription = z.infer<typeof SubscriptionSchema>;

/** Google could not answer (network, auth, throttling, 5xx, malformed body). Retryable; message is token-free. */
export class GooglePlayUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'GooglePlayUnavailableError';
  }
}

export interface GooglePlayClient {
  /**
   * The subscription purchase for `purchaseToken` in `packageName`, or `null` when Google does not
   * know the token for that package (invalid, another app's token, or expired long ago).
   * Rejects with `GooglePlayUnavailableError` when Google cannot answer.
   */
  getSubscription(packageName: string, purchaseToken: string): Promise<GooglePlaySubscription | null>;
  /** Acknowledges the purchase (Play refunds unacknowledged purchases after 3 days). */
  acknowledgeSubscription(packageName: string, productId: string, purchaseToken: string): Promise<void>;
}

/** A configured Play integration: the package every token is verified against plus its client. */
export interface GooglePlay {
  packageName: string;
  client: GooglePlayClient;
}

export interface GooglePlayEnvConfig {
  packageName: string;
  serviceAccount: { clientEmail: string; privateKey: string };
}

const ServiceAccountSchema = z.object({ client_email: z.string().min(1), private_key: z.string().min(1) });

/**
 * GOOGLE_PLAY_PACKAGE_NAME (com.chrisgoac.gymlog) + GOOGLE_PLAY_SERVICE_ACCOUNT_JSON (the downloaded
 * key file). `null` when either is missing or unusable; the JSON content is never logged.
 */
export function readGooglePlayConfig(env: Record<string, string | undefined> = process.env): GooglePlayEnvConfig | null {
  const packageName = env.GOOGLE_PLAY_PACKAGE_NAME?.trim();
  const raw = env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON;
  if (!packageName || !raw) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  const account = ServiceAccountSchema.safeParse(parsed);
  if (!account.success) return null;
  return { packageName, serviceAccount: { clientEmail: account.data.client_email, privateKey: account.data.private_key } };
}

export interface RestClientDeps {
  getAccessToken: () => Promise<string>;
  fetch?: typeof fetch;
  timeoutMs?: number;
}

/** `fetch`-based implementation of the two androidpublisher calls. Tests inject `fetch`; nothing here logs. */
export function createGooglePlayRestClient(deps: RestClientDeps): GooglePlayClient {
  const doFetch = deps.fetch ?? fetch;
  const timeoutMs = deps.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  // Every failure is rethrown with a fixed, token-free message and no `cause` (the cause could carry the URL).
  async function call(operation: string, url: string, method: 'GET' | 'POST'): Promise<Response> {
    let accessToken: string;
    try {
      accessToken = await deps.getAccessToken();
    } catch {
      throw new GooglePlayUnavailableError(`${operation}: access token unavailable`);
    }
    try {
      return await doFetch(url, {
        method,
        headers: { authorization: `Bearer ${accessToken}`, accept: 'application/json' },
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch {
      throw new GooglePlayUnavailableError(`${operation}: network error`);
    }
  }

  return {
    async getSubscription(packageName, purchaseToken) {
      const url = `${API_BASE}/${encodeURIComponent(packageName)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
      const res = await call('subscriptionsv2.get', url, 'GET');
      // 400 invalid token, 404 unknown (e.g. another package), 410 expired long ago: not ours to grant.
      if (res.status === 400 || res.status === 404 || res.status === 410) return null;
      if (!res.ok) throw new GooglePlayUnavailableError(`subscriptionsv2.get: http ${res.status}`);
      let body: unknown;
      try {
        body = await res.json();
      } catch {
        throw new GooglePlayUnavailableError('subscriptionsv2.get: malformed body');
      }
      const parsed = SubscriptionSchema.safeParse(body);
      if (!parsed.success) throw new GooglePlayUnavailableError('subscriptionsv2.get: unexpected body');
      return parsed.data;
    },
    async acknowledgeSubscription(packageName, productId, purchaseToken) {
      const url = `${API_BASE}/${encodeURIComponent(packageName)}/purchases/subscriptions/${encodeURIComponent(productId)}`
        + `/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`;
      const res = await call('subscriptions.acknowledge', url, 'POST');
      if (!res.ok) throw new GooglePlayUnavailableError(`subscriptions.acknowledge: http ${res.status}`);
    },
  };
}

let cached: { key: string; play: GooglePlay } | undefined;

/**
 * Production wiring from the environment, or `null` when Play is not configured (callers answer
 * 503 BILLING_UNAVAILABLE). Re-read per call so a deploy-time env change is honoured; the client
 * (and its cached access token) is reused while the config is unchanged.
 */
export function getGooglePlay(env: Record<string, string | undefined> = process.env): GooglePlay | null {
  const config = readGooglePlayConfig(env);
  if (!config) return null;
  const key = `${config.packageName}\u0000${config.serviceAccount.clientEmail}\u0000${config.serviceAccount.privateKey}`;
  if (cached?.key !== key) {
    const jwt = new JWT({
      email: config.serviceAccount.clientEmail,
      key: config.serviceAccount.privateKey,
      scopes: [ANDROID_PUBLISHER_SCOPE],
    });
    const client = createGooglePlayRestClient({
      getAccessToken: async () => {
        const { token } = await jwt.getAccessToken();
        if (!token) throw new Error('empty access token');
        return token;
      },
    });
    cached = { key, play: { packageName: config.packageName, client } };
  }
  return cached.play;
}
