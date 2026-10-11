import { enforceAiBudget } from '@/lib/api/v1/ai/budget';
import { IntelligentReportRequestSchema } from '@/lib/api/v1/ai/contracts';
import { requirePro } from '@/lib/api/v1/ai/guard';
import { aiUnavailable, readJsonBody } from '@/lib/api/v1/ai/http';
import { getReportDeps } from '@/lib/api/v1/ai/runtime';
import { withApiRoute } from '@/lib/api/v1/route';
import { deepseekConfigured } from '@/lib/coach-model';
import { getIntelligentReport } from '@/lib/intelligent-report';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';
// The model call itself is cut at REPORT_TIMEOUT_MS (60 s), inside this budget.
export const maxDuration = 90;

// Server-owned report keyed by the snapshot's fingerprint; never delivered through sync.
// Order: auth (withApiRoute) → Pro → cache. A cache hit needs neither DeepSeek nor AI budget; only a
// miss checks availability and spends the per-minute + daily AI quota before calling the model.
export const POST = withApiRoute(async (request, principal) => {
  await requirePro(principal);
  const body = IntelligentReportRequestSchema.parse(await readJsonBody(request));
  const result = await getIntelligentReport(getReportDeps(), principal.userId, body.snapshot, {
    beforeGenerate: async () => {
      if (!deepseekConfigured()) throw aiUnavailable();
      await enforceAiBudget(principal.userId);
    },
  });
  return Response.json(result, { headers: { 'cache-control': 'no-store' } });
});
