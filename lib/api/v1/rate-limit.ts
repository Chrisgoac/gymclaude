import { createHash } from 'node:crypto';
import { sql } from 'drizzle-orm';
import { getPoolDb, type PoolDb } from '@/db/pool';
import { RateLimitedError } from './errors';

export interface RateLimitPolicy {
  limit: number;
  windowMs: number;
}

export interface RateLimitDecision {
  allowed: boolean;
  remaining: number;
  retryAfterSeconds: number;
}

export const RATE_LIMIT_POLICIES = {
  sync: { limit: 120, windowMs: 60_000 },
  ai: { limit: 20, windowMs: 60_000 },
  media: { limit: 30, windowMs: 60_000 },
} as const satisfies Record<string, RateLimitPolicy>;

export function hashSubject(subject: string): string {
  return createHash('sha256').update(subject).digest('hex');
}

// Ventana fija; un único INSERT … ON CONFLICT es atómico bajo concurrencia.
export async function consumeRateLimit(
  scope: string,
  subject: string,
  policy: RateLimitPolicy,
  deps: { db?: Pick<PoolDb, 'execute'>; now?: number } = {},
): Promise<RateLimitDecision> {
  const now = deps.now ?? Date.now();
  const windowStart = Math.floor(now / policy.windowMs) * policy.windowMs;
  const db = deps.db ?? getPoolDb();
  const result = await db.execute(sql`
    INSERT INTO api_rate_buckets (scope, subject_hash, window_start, count)
    VALUES (${scope}, ${hashSubject(subject)}, ${windowStart}, 1)
    ON CONFLICT (scope, subject_hash, window_start)
    DO UPDATE SET count = api_rate_buckets.count + 1
    RETURNING count`);
  const count = Number((result.rows[0] as { count: number | string }).count);
  return {
    allowed: count <= policy.limit,
    remaining: Math.max(0, policy.limit - count),
    retryAfterSeconds: Math.max(1, Math.ceil((windowStart + policy.windowMs - now) / 1000)),
  };
}

export async function enforceRateLimit(
  policyName: keyof typeof RATE_LIMIT_POLICIES,
  subject: string,
): Promise<void> {
  const decision = await consumeRateLimit(policyName, subject, RATE_LIMIT_POLICIES[policyName]);
  if (!decision.allowed) throw new RateLimitedError(decision.retryAfterSeconds);
}
