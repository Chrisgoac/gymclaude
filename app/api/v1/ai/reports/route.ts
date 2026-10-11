import { IntelligentReportRequestSchema } from '@/lib/api/v1/ai/contracts';
import { requirePro } from '@/lib/api/v1/ai/guard';
import { aiUnavailable, readJsonBody } from '@/lib/api/v1/ai/http';
import { getReportDeps } from '@/lib/api/v1/ai/runtime';
import { enforceRateLimit } from '@/lib/api/v1/rate-limit';
import { withApiRoute } from '@/lib/api/v1/route';
import { deepseekConfigured } from '@/lib/coach-model';
import { getIntelligentReport } from '@/lib/intelligent-report';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';
// The model call itself is cut at REPORT_TIMEOUT_MS (60 s), inside this budget.
export const maxDuration = 90;

// Server-owned report keyed by the snapshot's fingerprint; never delivered through sync.
export const POST = withApiRoute(async (request, principal) => {
  await enforceRateLimit('ai', principal.userId);
  await requirePro(principal);
  if (!deepseekConfigured()) throw aiUnavailable();
  const body = IntelligentReportRequestSchema.parse(await readJsonBody(request));
  const result = await getIntelligentReport(getReportDeps(), principal.userId, body.snapshot);
  return Response.json(result, { headers: { 'cache-control': 'no-store' } });
});
