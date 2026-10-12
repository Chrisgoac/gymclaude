import { sql } from 'drizzle-orm';
import { getPoolDb, type PoolDb } from '@/db/pool';
import { isCompedPro } from '@/lib/entitlements/comped';

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

/**
 * `source = 'test'` rows (scripts/seed-test-entitlement.mjs) never grant Pro in a production
 * build: NODE_ENV === 'production' covers every Vercel deployment (production and preview) and any
 * `next start`. Local `next dev` and tests keep them usable. Read per call, never cached.
 */
export function testEntitlementsAllowed(env: NodeJS.ProcessEnv = process.env): boolean {
  return env.NODE_ENV !== 'production';
}

export interface EntitlementReader {
  /**
   * true iff the user has an entitlement that grants Pro at `now` (epoch ms); false when there is
   * none. Rejects when the store cannot be read: callers must fail closed, never grant.
   * Users in the `PRO_USER_IDS` allowlist (comped) are Pro without reading the store.
   */
  isPro(userId: string, now: number): Promise<boolean>;
}

/** Reads `subscription_entitlements`. The pool is resolved per call so a missing DB rejects. */
export function createDbEntitlementReader(
  getDb: () => Pick<PoolDb, 'execute'> = getPoolDb,
  allowTestEntitlements: () => boolean = testEntitlementsAllowed,
  isComped: (userId: string) => boolean = isCompedPro,
): EntitlementReader {
  // Single source of truth for the granting states: the SQL list is built from PRO_GRANTING_STATES.
  const granting = sql.join([...PRO_GRANTING_STATES].map((s) => sql`${s}`), sql`, `);
  return {
    async isPro(userId, now) {
      if (isComped(userId)) return true;
      const allowTest = allowTestEntitlements();
      const result = await getDb().execute(sql`
        SELECT 1 AS granted FROM subscription_entitlements
        WHERE user_id = ${userId}
          AND state IN (${granting})
          AND expires_at > ${now}
          AND (source <> 'test' OR ${allowTest})
        LIMIT 1`);
      return result.rows.length > 0;
    },
  };
}
