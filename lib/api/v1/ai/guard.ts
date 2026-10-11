import type { ApiPrincipal } from '@/lib/api/v1/auth';
import { ApiError } from '@/lib/api/v1/errors';
import { createDbEntitlementReader, type EntitlementReader } from '@/lib/entitlements/reader';

export const proRequired = () =>
  new ApiError(403, 'PRO_REQUIRED', 'An active Pro subscription is required', false);
export const entitlementUnavailable = () =>
  new ApiError(503, 'ENTITLEMENT_UNAVAILABLE', 'Subscription status is temporarily unavailable', true);

const defaultReader = createDbEntitlementReader();

export interface RequireProDeps {
  reader?: EntitlementReader;
  now?: () => number;
}

/**
 * Server-side Pro gate for every /api/v1/ai/* route (after `withApiRoute` authenticated the
 * principal). Fails closed: if the entitlement store errors, the request is refused with a
 * retryable 503, never granted. Android's own gating is presentation only.
 */
export async function requirePro(principal: ApiPrincipal, deps: RequireProDeps = {}): Promise<void> {
  const reader = deps.reader ?? defaultReader;
  const now = (deps.now ?? Date.now)();
  let pro: unknown;
  try {
    pro = await reader.isPro(principal.userId, now);
  } catch (error) {
    // Log the failure without user content; the wire body stays generic.
    console.error('[api/v1/ai] entitlement store unavailable', error instanceof Error ? error.name : typeof error);
    throw entitlementUnavailable();
  }
  if (typeof pro !== 'boolean') throw entitlementUnavailable();
  if (!pro) throw proRequired();
}
