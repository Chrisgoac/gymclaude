import { sql } from 'drizzle-orm';
import { OAuth2Client, type TokenPayload } from 'google-auth-library';
import { z } from 'zod';
import { getPoolDb, type PoolDb } from '@/db/pool';
import { grantsPro } from '@/lib/entitlements/reader';
import {
  GOOGLE_PLAY_PRODUCT_IDS,
  getGooglePlay,
  obfuscatedAccountIdFor,
  type GooglePlay,
  type GooglePlaySubscription,
} from './google-play';
import {
  hashPurchaseToken,
  lockTokenHash,
  projectGoogleState,
  upsertPlayInTx,
  type PlayEntitlementWrite,
} from './entitlements';

/**
 * Google Play Real-Time Developer Notifications (RTDN), delivered by a Pub/Sub push subscription to
 * POST /api/webhooks/google-play.
 *
 * - Authentication: the Google-signed OIDC token Pub/Sub attaches (`Authorization: Bearer <JWT>`),
 *   checked for signature (Google JWKS), issuer, audience and the push service account.
 * - Idempotency: `billing_events.message_id` (Pub/Sub messageId). A processed message is acknowledged
 *   again without reprocessing; a message that failed transiently stays unprocessed and is retried.
 * - Authority: the notification only says "look at this token". The subscription is always refetched
 *   from Google and projected with Task 1's mapping, so duplicate or out-of-order notifications converge
 *   on Google's current state. The stale-write guard (`last_verified_at`) keeps an older fetch from
 *   overwriting a newer one.
 * - Event row and entitlement changes commit in ONE transaction.
 * - Logs: message id, notification kind/type, a short token-hash prefix and the outcome. Never the
 *   token, the full hash or the JWT.
 */

// ── Pub/Sub push envelope + DeveloperNotification ──

export const PubSubPushSchema = z.object({
  message: z.object({
    data: z.string().max(64 * 1024),
    messageId: z.string().min(1).max(256),
    publishTime: z.string().optional(),
  }),
  subscription: z.string().optional(),
});
export type PubSubPush = z.infer<typeof PubSubPushSchema>;

const PurchaseTokenSchema = z.string().min(1).max(4096).regex(/^[A-Za-z0-9._-]+$/);
const NotificationSchema = z.object({
  packageName: z.string().min(1),
  eventTimeMillis: z.string().regex(/^\d{1,16}$/),
  subscriptionNotification: z.object({
    notificationType: z.number().int(),
    purchaseToken: PurchaseTokenSchema,
  }).optional(),
  voidedPurchaseNotification: z.object({
    purchaseToken: PurchaseTokenSchema,
    productType: z.number().int(),
  }).optional(),
  testNotification: z.object({}).passthrough().optional(),
});

export type DecodedNotification =
  | { kind: 'subscription'; packageName: string; eventTime: number; notificationType: number; purchaseToken: string }
  | { kind: 'voided'; packageName: string; eventTime: number; productType: number; purchaseToken: string }
  | { kind: 'test'; packageName: string; eventTime: number }
  | { kind: 'unknown'; packageName: string; eventTime: number };

/** `message.data` (base64 JSON DeveloperNotification) → typed notification, or `null` if malformed. */
export function decodeNotification(data: string): DecodedNotification | null {
  if (!/^[A-Za-z0-9+/_-]*={0,2}$/.test(data)) return null;
  let json: unknown;
  try {
    json = JSON.parse(Buffer.from(data, 'base64').toString('utf8'));
  } catch {
    return null;
  }
  const parsed = NotificationSchema.safeParse(json);
  if (!parsed.success) return null;
  const n = parsed.data;
  const base = { packageName: n.packageName, eventTime: Number(n.eventTimeMillis) };
  if (n.subscriptionNotification) {
    return { ...base, kind: 'subscription', ...n.subscriptionNotification };
  }
  if (n.voidedPurchaseNotification) {
    return { ...base, kind: 'voided', ...n.voidedPurchaseNotification };
  }
  if (n.testNotification) return { ...base, kind: 'test' };
  return { ...base, kind: 'unknown' }; // one-time products or a type added later: acknowledged, ignored
}

// ── Pub/Sub OIDC authentication ──

export interface RtdnConfig {
  /** Audience configured on the push subscription (normally the webhook URL). */
  audience: string;
  /** Service account the push subscription authenticates as. */
  serviceAccountEmail: string;
}

/** GOOGLE_PLAY_RTDN_AUDIENCE + GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT, or `null` (webhook answers 503). */
export function readRtdnConfig(env: Record<string, string | undefined> = process.env): RtdnConfig | null {
  const audience = env.GOOGLE_PLAY_RTDN_AUDIENCE?.trim();
  const serviceAccountEmail = env.GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT?.trim();
  if (!audience || !serviceAccountEmail) return null;
  return { audience, serviceAccountEmail };
}

/** Google's certificates could not be fetched: retryable (503), not a rejection. */
export class RtdnAuthUnavailableError extends Error {
  constructor() {
    super('Google signing keys unavailable');
    this.name = 'RtdnAuthUnavailableError';
  }
}

export interface PubSubAuthVerifier {
  /** 'ok' for a valid push token, 'invalid' otherwise; rejects with `RtdnAuthUnavailableError` on a JWKS outage. */
  verify(authorization: string | null): Promise<'ok' | 'invalid'>;
}

/** The two `OAuth2Client` methods the verifier uses (tests pass a client with local certificates). */
export interface OidcClient {
  getFederatedSignonCertsAsync(): Promise<unknown>;
  verifyIdToken(options: { idToken: string; audience: string }): Promise<{ getPayload(): TokenPayload | undefined }>;
}

const GOOGLE_ISSUERS = new Set(['accounts.google.com', 'https://accounts.google.com']);

export function createGoogleOidcVerifier(config: RtdnConfig, client: OidcClient = new OAuth2Client()): PubSubAuthVerifier {
  return {
    async verify(authorization) {
      const match = /^Bearer ([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/.exec(authorization ?? '');
      if (!match || match[1].length > 8192) return 'invalid';
      try {
        await client.getFederatedSignonCertsAsync(); // cached by the client; a failure here is an outage
      } catch {
        throw new RtdnAuthUnavailableError();
      }
      let payload: TokenPayload | undefined;
      try {
        // Signature (Google certs), expiry, issuer and audience. Its errors embed the JWT: never logged.
        payload = (await client.verifyIdToken({ idToken: match[1], audience: config.audience })).getPayload();
      } catch {
        return 'invalid';
      }
      if (!payload || !GOOGLE_ISSUERS.has(payload.iss)) return 'invalid';
      if (payload.email !== config.serviceAccountEmail || payload.email_verified !== true) return 'invalid';
      return 'ok';
    },
  };
}

// ── Store ──

export type NotificationKind = 'subscription' | 'voided' | 'test' | 'unknown' | 'malformed';

export interface RtdnEvent {
  messageId: string;
  /** Google's eventTimeMillis (publish time / receipt time when the data is malformed). */
  eventTime: number;
  notificationKind: NotificationKind;
  notificationType: number | null;
  purchaseTokenHash: string | null;
  receivedAt: number;
}

export interface RtdnCommit {
  event: RtdnEvent;
  upserts: PlayEntitlementWrite[];
  /** Upgrade/downgrade: the replaced token's row of the same user becomes EXPIRED. */
  supersede?: { userId: string; purchaseTokenHash: string; at: number };
  outcome: string;
  processedAt: number;
}

export interface TokenOwner {
  userId: string;
  productId: string;
  expiresAt: number;
}

export interface RtdnStore {
  isProcessed(messageId: string): Promise<boolean>;
  /** Inserts the event as received (unprocessed) if absent. */
  recordReceived(event: RtdnEvent): Promise<void>;
  findByTokenHash(purchaseTokenHash: string): Promise<TokenOwner | null>;
  /**
   * One transaction: event row (locked), entitlement writes, mark processed. 'duplicate' (nothing
   * written) when a concurrent delivery already processed the message.
   */
  commit(commit: RtdnCommit): Promise<'committed' | 'duplicate'>;
}

export function createDbRtdnStore(getDb: () => PoolDb = getPoolDb): RtdnStore {
  const insertEvent = (e: RtdnEvent) => sql`
    INSERT INTO billing_events
      (message_id, event_time, notification_kind, notification_type, purchase_token_hash, received_at)
    VALUES (${e.messageId}, ${e.eventTime}, ${e.notificationKind}, ${e.notificationType}, ${e.purchaseTokenHash}, ${e.receivedAt})
    ON CONFLICT (message_id) DO NOTHING`;
  return {
    async isProcessed(messageId) {
      const r = await getDb().execute(sql`SELECT processed_at FROM billing_events WHERE message_id = ${messageId}`);
      return r.rows.length > 0 && r.rows[0].processed_at !== null;
    },
    async recordReceived(event) {
      await getDb().execute(insertEvent(event));
    },
    async findByTokenHash(hash) {
      // Task 1 binds a token hash to exactly one user; the index makes this lookup cheap.
      const r = await getDb().execute(sql`
        SELECT user_id, product_id, expires_at FROM subscription_entitlements
        WHERE purchase_token_hash = ${hash} AND source = 'play'
        ORDER BY last_verified_at DESC
        LIMIT 1`);
      const row = r.rows[0] as Record<string, unknown> | undefined;
      return row ? { userId: String(row.user_id), productId: String(row.product_id), expiresAt: Number(row.expires_at) } : null;
    },
    async commit(c) {
      return getDb().transaction(async (tx) => {
        await tx.execute(insertEvent(c.event));
        const locked = await tx.execute(sql`SELECT processed_at FROM billing_events WHERE message_id = ${c.event.messageId} FOR UPDATE`);
        if (locked.rows[0]?.processed_at !== null && locked.rows[0]?.processed_at !== undefined) return 'duplicate' as const;
        for (const write of c.upserts) {
          if (await upsertPlayInTx(tx, write) === 'already_bound') {
            console.warn('[billing/rtdn] token bound to another account; not written', JSON.stringify({ messageId: c.event.messageId }));
          }
        }
        if (c.supersede) {
          const s = c.supersede;
          await lockTokenHash(tx, s.purchaseTokenHash);
          await tx.execute(sql`
            UPDATE subscription_entitlements
            SET state = 'EXPIRED', auto_renewing = false, last_verified_at = ${s.at}, updated_at = ${s.at}
            WHERE user_id = ${s.userId} AND purchase_token_hash = ${s.purchaseTokenHash} AND source = 'play'
              AND last_verified_at <= ${s.at}`);
        }
        await tx.execute(sql`
          UPDATE billing_events SET outcome = ${c.outcome}, processed_at = ${c.processedAt}
          WHERE message_id = ${c.event.messageId}`);
        return 'committed' as const;
      });
    },
  };
}

// ── Processing ──

export interface RtdnDeps {
  play: GooglePlay;
  store: RtdnStore;
  now: () => number;
}

export interface RtdnResult {
  /** 200 = acknowledge (Pub/Sub stops redelivering); 503 = retry later. */
  status: 200 | 503;
  outcome: string;
}

type Decision =
  | { kind: 'commit'; outcome: string; upserts?: PlayEntitlementWrite[]; supersede?: RtdnCommit['supersede'] }
  | { kind: 'retry'; outcome: 'google_unavailable' | 'store_unavailable' };

const ack = (outcome: string): Decision => ({ kind: 'commit', outcome });

/** Pub/Sub delivery → idempotent entitlement update. Never throws. */
export async function processPushMessage(deps: RtdnDeps, push: PubSubPush): Promise<RtdnResult> {
  const { messageId, data, publishTime } = push.message;
  const receivedAt = deps.now();
  const notification = decodeNotification(data);
  const tokenHash = notification && 'purchaseToken' in notification ? hashPurchaseToken(notification.purchaseToken) : null;
  const published = publishTime ? Date.parse(publishTime) : Number.NaN;
  const event: RtdnEvent = {
    messageId,
    eventTime: notification?.eventTime ?? (Number.isFinite(published) ? published : receivedAt),
    notificationKind: notification?.kind ?? 'malformed',
    notificationType: notification?.kind === 'subscription' ? notification.notificationType : null,
    purchaseTokenHash: tokenHash,
    receivedAt,
  };
  const done = (result: RtdnResult): RtdnResult => {
    console.info('[billing/rtdn]', JSON.stringify({
      messageId, kind: event.notificationKind, type: event.notificationType,
      tokenHash: tokenHash?.slice(0, 12) ?? null, outcome: result.outcome, status: result.status,
    }));
    return result;
  };

  try {
    if (await deps.store.isProcessed(messageId)) return done({ status: 200, outcome: 'duplicate' });
  } catch {
    return done({ status: 503, outcome: 'store_unavailable' });
  }

  let decision: Decision;
  if (!notification) decision = ack('malformed');
  else if (notification.packageName !== deps.play.packageName) decision = ack('wrong_package');
  else if (notification.kind === 'test') decision = ack('test');
  else if (notification.kind === 'unknown') decision = ack('ignored_type');
  else if (notification.kind === 'voided' && notification.productType !== 1) decision = ack('ignored_type'); // one-time product
  else decision = await decide(deps, notification);

  if (decision.kind === 'retry') {
    await deps.store.recordReceived(event).catch(() => {}); // trace only; Pub/Sub redelivers either way
    return done({ status: 503, outcome: decision.outcome });
  }
  try {
    const committed = await deps.store.commit({
      event, upserts: decision.upserts ?? [], supersede: decision.supersede, outcome: decision.outcome, processedAt: deps.now(),
    });
    return done({ status: 200, outcome: committed === 'duplicate' ? 'duplicate' : decision.outcome });
  } catch {
    return done({ status: 503, outcome: 'store_unavailable' });
  }
}

/** The Pro-relevant line item (one of our products) of a Google subscription. */
const ourLineItem = (sub: GooglePlaySubscription) =>
  sub.lineItems.find((item) => (GOOGLE_PLAY_PRODUCT_IDS as readonly string[]).includes(item.productId));

/** Google subscription → stored write for `userId`, or the reason it is not written. */
function projectFor(sub: GooglePlaySubscription, userId: string, tokenHash: string, verifiedAt: number): PlayEntitlementWrite | string {
  if (sub.externalAccountIdentifiers?.obfuscatedExternalAccountId !== obfuscatedAccountIdFor(userId)) return 'account_mismatch';
  if (sub.subscriptionState === 'SUBSCRIPTION_STATE_PENDING') return 'pending';
  if (sub.subscriptionState === 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED') return 'pending_canceled';
  const line = ourLineItem(sub);
  if (!line) return 'invalid_product';
  const projection = projectGoogleState(sub.subscriptionState, line.autoRenewingPlan?.autoRenewEnabled ?? false);
  const expiresAt = line.expiryTime ? Date.parse(line.expiryTime) : Number.NaN;
  if (!projection || !Number.isFinite(expiresAt)) return 'unexpected_state';
  return { userId, productId: line.productId, purchaseTokenHash: tokenHash, ...projection, expiresAt, verifiedAt };
}

async function decide(
  deps: RtdnDeps,
  n: Extract<DecodedNotification, { kind: 'subscription' | 'voided' }>,
): Promise<Decision> {
  const tokenHash = hashPurchaseToken(n.purchaseToken);
  let sub: GooglePlaySubscription | null;
  try {
    sub = await deps.play.client.getSubscription(deps.play.packageName, n.purchaseToken);
  } catch {
    return { kind: 'retry', outcome: 'google_unavailable' };
  }
  // Time of this Google answer: the stale-write guard orders writes by it.
  const verifiedAt = deps.now();
  const linkedHash = sub?.linkedPurchaseToken ? hashPurchaseToken(sub.linkedPurchaseToken) : null;

  let owner: TokenOwner | null;
  let linkedOwner: TokenOwner | null = null;
  try {
    owner = await deps.store.findByTokenHash(tokenHash);
    if (linkedHash) linkedOwner = await deps.store.findByTokenHash(linkedHash);
  } catch {
    return { kind: 'retry', outcome: 'store_unavailable' };
  }

  if (n.kind === 'voided') {
    // Refund/chargeback. Revoke unless Google still entitles the purchase (e.g. a partial refund).
    if (!owner) return ack('unknown_token');
    const write = sub ? projectFor(sub, owner.userId, tokenHash, verifiedAt) : 'token_unknown_to_google';
    if (typeof write !== 'string' && grantsPro(write, verifiedAt)) return { kind: 'commit', outcome: 'updated', upserts: [write] };
    return {
      kind: 'commit',
      outcome: 'revoked',
      upserts: [{
        userId: owner.userId, productId: owner.productId, purchaseTokenHash: tokenHash, state: 'REVOKED',
        expiresAt: Math.min(owner.expiresAt, verifiedAt), autoRenewing: false, verifiedAt,
      }],
    };
  }

  if (!sub) return ack('token_unknown_to_google');
  // A brand-new token (purchase not verified by the client yet) is only adopted when it replaces a
  // token we already know (upgrade/downgrade/resubscribe); otherwise the client's verify binds it.
  const userId = owner?.userId ?? linkedOwner?.userId;
  if (!userId) return ack('unknown_token');
  const write = projectFor(sub, userId, tokenHash, verifiedAt);
  if (typeof write === 'string') return ack(write);
  const supersede = linkedHash && linkedOwner?.userId === userId && linkedHash !== tokenHash
    ? { userId, purchaseTokenHash: linkedHash, at: verifiedAt }
    : undefined;
  return { kind: 'commit', outcome: 'updated', upserts: [write], supersede };
}

// ── Production wiring ──

export interface RtdnRuntime {
  /** `null` when the push authentication is not configured (webhook answers 503). */
  verifier: PubSubAuthVerifier | null;
  /** `null` when Google Play is not configured (webhook answers 503). */
  play: GooglePlay | null;
  store: RtdnStore;
  now: () => number;
}

const defaultStore = createDbRtdnStore();
let cachedVerifier: { key: string; verifier: PubSubAuthVerifier } | undefined;

/** Production dependencies. Route tests mock this function. */
export function getRtdnRuntime(env: Record<string, string | undefined> = process.env): RtdnRuntime {
  const config = readRtdnConfig(env);
  let verifier: PubSubAuthVerifier | null = null;
  if (config) {
    const key = `${config.audience}\u0000${config.serviceAccountEmail}`;
    // One OAuth2Client per config keeps Google's certificates cached across requests.
    if (cachedVerifier?.key !== key) cachedVerifier = { key, verifier: createGoogleOidcVerifier(config) };
    verifier = cachedVerifier.verifier;
  }
  return { verifier, play: getGooglePlay(env), store: defaultStore, now: Date.now };
}
