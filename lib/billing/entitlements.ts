import { createHash } from 'node:crypto';
import { sql } from 'drizzle-orm';
import { z } from 'zod';
import { getPoolDb, type PoolDb } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import { entitlementUnavailable } from '@/lib/api/v1/ai/guard';
import {
  PRO_GRANTING_STATES,
  grantsPro,
  testEntitlementsAllowed,
  type EntitlementState,
} from '@/lib/entitlements/reader';
import {
  GOOGLE_PLAY_PRODUCT_IDS,
  getGooglePlay,
  obfuscatedAccountIdFor,
  type GooglePlay,
  type GooglePlaySubscription,
} from './google-play';

// ── Wire contracts ──

/** Play purchase tokens are URL-safe strings (~150–500 chars); 4096 leaves ample room. */
export const VerifyPurchaseRequestSchema = z.object({
  productId: z.enum(GOOGLE_PLAY_PRODUCT_IDS),
  purchaseToken: z.string().min(1).max(4096).regex(/^[A-Za-z0-9._-]+$/),
}).strict();
export type VerifyPurchaseRequest = z.infer<typeof VerifyPurchaseRequestSchema>;

export const ENTITLEMENT_RESPONSE_STATES = ['NONE', 'ACTIVE', 'GRACE', 'PAUSED', 'CANCELED', 'EXPIRED'] as const;
export const EntitlementResponseSchema = z.object({
  tier: z.enum(['FREE', 'PRO']),
  productId: z.string().nullable(),
  state: z.enum(ENTITLEMENT_RESPONSE_STATES),
  /** Epoch ms of the end of the paid/grace period, or null without any entitlement. */
  validUntil: z.number().int().nullable(),
  /** Server clock (epoch ms) the answer was computed at: compare `validUntil` with it, not with the device clock. */
  serverTime: z.number().int(),
}).strict();
export type EntitlementResponse = z.infer<typeof EntitlementResponseSchema>;

// ── Stable errors ──

export const billingUnavailable = () =>
  new ApiError(503, 'BILLING_UNAVAILABLE', 'Purchase verification is temporarily unavailable', true);
export const purchaseInvalid = () =>
  new ApiError(422, 'PURCHASE_INVALID', 'The purchase is not a valid GymLog subscription', false);
export const purchaseAccountMismatch = () =>
  new ApiError(403, 'PURCHASE_ACCOUNT_MISMATCH', 'The purchase was made for another account', false);
export const purchaseAlreadyBound = () =>
  new ApiError(409, 'PURCHASE_ALREADY_BOUND', 'The purchase is already linked to another account', false);
export const purchasePending = () =>
  new ApiError(409, 'PURCHASE_PENDING', 'The purchase is still pending payment', true);

// ── Store ──

export interface EntitlementRow {
  userId: string;
  productId: string;
  purchaseTokenHash: string;
  state: EntitlementState;
  expiresAt: number;
  autoRenewing: boolean;
  lastVerifiedAt: number;
  source: 'play' | 'test';
}

export interface PlayEntitlementWrite {
  userId: string;
  productId: string;
  purchaseTokenHash: string;
  state: EntitlementState;
  expiresAt: number;
  autoRenewing: boolean;
  /** Server time of this Google verification. */
  verifiedAt: number;
}

export interface EntitlementStore {
  /**
   * Upserts the verified row by (user, token hash). `already_bound` (nothing written) when the
   * hash already belongs to another user.
   */
  upsertPlay(write: PlayEntitlementWrite): Promise<'bound' | 'already_bound'>;
  /** The caller's best row: a Pro-granting one with the latest expiry, else the latest expiry; test rows follow isPro. */
  findBest(userId: string, now: number): Promise<EntitlementRow | null>;
}

/** SHA-256 hex of the purchase token: the only form a token is ever stored in. */
export function hashPurchaseToken(purchaseToken: string): string {
  return createHash('sha256').update(purchaseToken).digest('hex');
}

export function createDbEntitlementStore(
  getDb: () => PoolDb = getPoolDb,
  allowTestEntitlements: () => boolean = testEntitlementsAllowed,
): EntitlementStore {
  const granting = sql.join([...PRO_GRANTING_STATES].map((s) => sql`${s}`), sql`, `);
  return {
    async upsertPlay(w) {
      return getDb().transaction(async (tx) => {
        // Serializes every write of this token hash (the PK is per user, so two users could
        // otherwise both bind the same token concurrently).
        await tx.execute(sql`SELECT pg_advisory_xact_lock(hashtextextended(${`billing-token:${w.purchaseTokenHash}`}, 0))`);
        const other = await tx.execute(sql`
          SELECT 1 FROM subscription_entitlements
          WHERE purchase_token_hash = ${w.purchaseTokenHash} AND user_id <> ${w.userId}
          LIMIT 1`);
        if (other.rows.length > 0) return 'already_bound' as const;
        // Google's state at verification time wins; an older verification never overwrites a newer one.
        await tx.execute(sql`
          INSERT INTO subscription_entitlements
            (user_id, product_id, purchase_token_hash, state, expires_at, auto_renewing, last_verified_at, source, created_at, updated_at)
          VALUES (${w.userId}, ${w.productId}, ${w.purchaseTokenHash}, ${w.state}, ${w.expiresAt}, ${w.autoRenewing},
                  ${w.verifiedAt}, 'play', ${w.verifiedAt}, ${w.verifiedAt})
          ON CONFLICT (user_id, purchase_token_hash) DO UPDATE SET
            product_id = EXCLUDED.product_id,
            state = EXCLUDED.state,
            expires_at = EXCLUDED.expires_at,
            auto_renewing = EXCLUDED.auto_renewing,
            last_verified_at = EXCLUDED.last_verified_at,
            source = 'play',
            updated_at = EXCLUDED.updated_at
          WHERE subscription_entitlements.last_verified_at <= EXCLUDED.last_verified_at`);
        return 'bound' as const;
      });
    },
    async findBest(userId, now) {
      const allowTest = allowTestEntitlements();
      const result = await getDb().execute(sql`
        SELECT product_id, purchase_token_hash, state, expires_at, auto_renewing, last_verified_at, source
        FROM subscription_entitlements
        WHERE user_id = ${userId} AND (source <> 'test' OR ${allowTest})
        ORDER BY (state IN (${granting}) AND expires_at > ${now}) DESC, expires_at DESC, last_verified_at DESC
        LIMIT 1`);
      const r = result.rows[0] as Record<string, unknown> | undefined;
      if (!r) return null;
      return {
        userId,
        productId: String(r.product_id),
        purchaseTokenHash: String(r.purchase_token_hash),
        state: r.state as EntitlementState,
        expiresAt: Number(r.expires_at),
        autoRenewing: Boolean(r.auto_renewing),
        lastVerifiedAt: Number(r.last_verified_at),
        source: r.source as 'play' | 'test',
      };
    },
  };
}

// ── Projection ──

/** Google `subscriptionState` → stored state. CANCELED keeps access until expiry (ACTIVE, no auto-renew). */
function projectGoogleState(googleState: string, autoRenew: boolean): { state: EntitlementState; autoRenewing: boolean } | null {
  switch (googleState) {
    case 'SUBSCRIPTION_STATE_ACTIVE': return { state: 'ACTIVE', autoRenewing: autoRenew };
    case 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD': return { state: 'GRACE', autoRenewing: autoRenew };
    case 'SUBSCRIPTION_STATE_ON_HOLD': return { state: 'ON_HOLD', autoRenewing: autoRenew };
    case 'SUBSCRIPTION_STATE_PAUSED': return { state: 'PAUSED', autoRenewing: autoRenew };
    case 'SUBSCRIPTION_STATE_CANCELED': return { state: 'ACTIVE', autoRenewing: false };
    case 'SUBSCRIPTION_STATE_EXPIRED': return { state: 'EXPIRED', autoRenewing: autoRenew };
    default: return null; // UNSPECIFIED or a state this server does not know: never grant.
  }
}

/** Stored row → wire answer. `tier` follows isPro exactly (`grantsPro`). */
export function toEntitlementResponse(row: EntitlementRow | null, now: number): EntitlementResponse {
  if (!row) return { tier: 'FREE', productId: null, state: 'NONE', validUntil: null, serverTime: now };
  const pro = grantsPro(row, now);
  let state: EntitlementResponse['state'];
  switch (row.state) {
    case 'ACTIVE':
    case 'GRACE':
      state = !pro ? 'EXPIRED' : row.state === 'ACTIVE' && !row.autoRenewing ? 'CANCELED' : row.state;
      break;
    case 'ON_HOLD': // account hold: payment to fix in Play, like a pause it is recoverable without a new purchase
    case 'PAUSED':
      state = 'PAUSED';
      break;
    case 'CANCELED':
      state = 'CANCELED';
      break;
    default: // EXPIRED, REVOKED
      state = 'EXPIRED';
  }
  return { tier: pro ? 'PRO' : 'FREE', productId: row.productId, state, validUntil: row.expiresAt, serverTime: now };
}

// ── Use cases ──

export interface BillingDeps {
  /** `null` when Play is not configured on this deployment. */
  play: GooglePlay | null;
  store: EntitlementStore;
  now: () => number;
}

// Logs carry only fixed text and an error class name: never tokens, hashes, account ids or Google bodies.
const errorName = (error: unknown) => (error instanceof Error ? error.name : typeof error);

async function bestFor(store: EntitlementStore, userId: string, now: number): Promise<EntitlementRow | null> {
  try {
    return await store.findBest(userId, now);
  } catch (error) {
    console.error('[billing] entitlement store unavailable', errorName(error));
    throw entitlementUnavailable();
  }
}

/**
 * Verifies a Play subscription purchase for `userId` and projects Google's state into
 * `subscription_entitlements`. Order: Google lookup → token known for our package → bound to this
 * account (obfuscatedExternalAccountId) → not pending → requested product present → persist → acknowledge.
 * Returns the caller's overall entitlement afterwards.
 */
export async function verifyPurchase(deps: BillingDeps, userId: string, request: VerifyPurchaseRequest): Promise<EntitlementResponse> {
  const { play, store } = deps;
  if (!play) {
    console.error('[billing] Google Play is not configured');
    throw billingUnavailable();
  }

  let sub: GooglePlaySubscription | null;
  try {
    sub = await play.client.getSubscription(play.packageName, request.purchaseToken);
  } catch (error) {
    console.error('[billing] Google Play unavailable', errorName(error));
    throw billingUnavailable();
  }
  if (!sub) throw purchaseInvalid();
  if (sub.externalAccountIdentifiers?.obfuscatedExternalAccountId !== obfuscatedAccountIdFor(userId)) {
    throw purchaseAccountMismatch();
  }
  if (sub.subscriptionState === 'SUBSCRIPTION_STATE_PENDING') throw purchasePending();
  if (sub.subscriptionState === 'SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED') throw purchaseInvalid();

  const line = sub.lineItems.find((item) => item.productId === request.productId);
  if (!line) throw purchaseInvalid();

  const projection = projectGoogleState(sub.subscriptionState, line.autoRenewingPlan?.autoRenewEnabled ?? false);
  const expiresAt = line.expiryTime ? Date.parse(line.expiryTime) : Number.NaN;
  if (!projection || !Number.isFinite(expiresAt)) {
    console.error('[billing] unexpected Google Play subscription shape');
    throw billingUnavailable();
  }

  const now = deps.now();
  let outcome: 'bound' | 'already_bound';
  try {
    outcome = await store.upsertPlay({
      userId,
      productId: request.productId,
      purchaseTokenHash: hashPurchaseToken(request.purchaseToken),
      state: projection.state,
      expiresAt,
      autoRenewing: projection.autoRenewing,
      verifiedAt: now,
    });
  } catch (error) {
    console.error('[billing] entitlement store unavailable', errorName(error));
    throw entitlementUnavailable();
  }
  if (outcome === 'already_bound') {
    console.warn('[billing] purchase token already bound to another account');
    throw purchaseAlreadyBound();
  }

  // Only after the grant is stored: never keep Google's money without the entitlement. A failure is
  // not fatal (the Android client also acknowledges, and the next verify retries while still pending).
  if (sub.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_PENDING') {
    try {
      await play.client.acknowledgeSubscription(play.packageName, request.productId, request.purchaseToken);
    } catch (error) {
      console.warn('[billing] acknowledgement failed; retried on the next verify', errorName(error));
    }
  }

  return toEntitlementResponse(await bestFor(store, userId, now), now);
}

/** The caller's entitlement (GET /api/v1/billing/entitlement). Never needs Google. */
export async function readEntitlement(deps: Pick<BillingDeps, 'store' | 'now'>, userId: string): Promise<EntitlementResponse> {
  const now = deps.now();
  return toEntitlementResponse(await bestFor(deps.store, userId, now), now);
}

const defaultStore = createDbEntitlementStore();

/** Production wiring. Route tests mock this function. */
export function getBillingDeps(): BillingDeps {
  return { play: getGooglePlay(), store: defaultStore, now: Date.now };
}
