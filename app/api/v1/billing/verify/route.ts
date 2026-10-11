import { readJsonBody } from '@/lib/api/v1/ai/http';
import { enforceRateLimit } from '@/lib/api/v1/rate-limit';
import { withApiRoute } from '@/lib/api/v1/route';
import { VerifyPurchaseRequestSchema, getBillingDeps, verifyPurchase } from '@/lib/billing/entitlements';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

const MAX_VERIFY_BODY_BYTES = 8 * 1024;

// Order: auth (withApiRoute) → billing rate limit → strict body → Google verification → projection.
// Only the server grants Pro: the client sends a product id and a purchase token, never a state.
export const POST = withApiRoute(async (request, principal) => {
  await enforceRateLimit('billing', principal.userId);
  const body = VerifyPurchaseRequestSchema.parse(await readJsonBody(request, MAX_VERIFY_BODY_BYTES));
  const result = await verifyPurchase(getBillingDeps(), principal.userId, body);
  return Response.json(result, { headers: { 'cache-control': 'no-store' } });
});
