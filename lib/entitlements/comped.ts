/**
 * Comped Pro without a Play purchase: `PRO_USER_IDS` is a comma-separated allowlist of Clerk user
 * ids (whitespace trimmed, empty entries ignored; unset or empty = nobody). Read per call so a
 * Vercel env change applies on the next deployment without code changes.
 */
export function compedProUserIds(env: NodeJS.ProcessEnv = process.env): ReadonlySet<string> {
  const raw = env.PRO_USER_IDS ?? '';
  return new Set(raw.split(',').map((id) => id.trim()).filter((id) => id.length > 0));
}

export function isCompedPro(userId: string, env: NodeJS.ProcessEnv = process.env): boolean {
  return userId.length > 0 && compedProUserIds(env).has(userId);
}

/**
 * Validity reported to clients for a comped grant. The Android app shows a cached Pro only until
 * `validUntil` and re-asks the server then, so a rolling window keeps offline use working while a
 * removal from the allowlist takes effect within this period.
 */
export const COMPED_VALIDITY_MS = 7 * 24 * 60 * 60 * 1000;
