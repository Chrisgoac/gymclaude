import { enforceRateLimit } from '@/lib/api/v1/rate-limit';
import { withApiRoute } from '@/lib/api/v1/route';
import { getBillingDeps, readEntitlement } from '@/lib/billing/entitlements';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

// The caller's server-verified entitlement (from subscription_entitlements; never asks Google).
export const GET = withApiRoute(async (_request, principal) => {
  await enforceRateLimit('billing', principal.userId);
  const result = await readEntitlement(getBillingDeps(), principal.userId);
  return Response.json(result, { headers: { 'cache-control': 'no-store' } });
});
