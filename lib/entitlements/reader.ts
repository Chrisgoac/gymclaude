import { sql } from 'drizzle-orm';
import { getPoolDb, type PoolDb } from '@/db/pool';

/**
 * States of a server-verified subscription entitlement (`subscription_entitlements.state`).
 * Phase 6 maps Google Play's subscription state onto them:
 * - ACTIVE    paid period running (a canceled-but-not-yet-expired purchase stays ACTIVE with
 *             `auto_renewing = false` until `expires_at`).
 * - GRACE     payment failed, Play grace period: the user keeps access.
 * - ON_HOLD   account hold after grace: no access.
 * - PAUSED    user-paused subscription: no access.
 * - CANCELED  canceled with no remaining entitled time: no access.
 * - EXPIRED   period ended: no access.
 * - REVOKED   refunded/revoked: no access.
 */
export const ENTITLEMENT_STATES = ['ACTIVE', 'GRACE', 'ON_HOLD', 'PAUSED', 'CANCELED', 'EXPIRED', 'REVOKED'] as const;
export type EntitlementState = (typeof ENTITLEMENT_STATES)[number];

/** Only these states grant Pro, and only while `expires_at` is in the future. */
export const PRO_GRANTING_STATES: ReadonlySet<EntitlementState> = new Set(['ACTIVE', 'GRACE']);

export function grantsPro(row: { state: string; expiresAt: number }, now: number): boolean {
  return PRO_GRANTING_STATES.has(row.state as EntitlementState) && row.expiresAt > now;
}

export interface EntitlementReader {
  /**
   * true iff the user has an entitlement that grants Pro at `now` (epoch ms); false when there is
   * none. Rejects when the store cannot be read: callers must fail closed, never grant.
   */
  isPro(userId: string, now: number): Promise<boolean>;
}

/** Reads `subscription_entitlements`. The pool is resolved per call so a missing DB rejects. */
export function createDbEntitlementReader(
  getDb: () => Pick<PoolDb, 'execute'> = getPoolDb,
): EntitlementReader {
  return {
    async isPro(userId, now) {
      const result = await getDb().execute(sql`
        SELECT 1 AS granted FROM subscription_entitlements
        WHERE user_id = ${userId}
          AND state IN ('ACTIVE', 'GRACE')
          AND expires_at > ${now}
        LIMIT 1`);
      return result.rows.length > 0;
    },
  };
}
