import { enforceAiBudget } from '@/lib/api/v1/ai/budget';
import { CoachRequestSchema } from '@/lib/api/v1/ai/contracts';
import { requirePro } from '@/lib/api/v1/ai/guard';
import { aiUnavailable, readJsonBody } from '@/lib/api/v1/ai/http';
import { getCoachDeps } from '@/lib/api/v1/ai/runtime';
import { withApiRoute } from '@/lib/api/v1/route';
import { deepseekConfigured } from '@/lib/coach-model';
import { NDJSON_CONTENT_TYPE, startCoachTurn } from '@/lib/coach-stream';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';
// The model call itself is cut at COACH_TIMEOUT_MS (45 s), inside this budget.
export const maxDuration = 60;

export const POST = withApiRoute(async (request, principal) => {
  await enforceAiBudget(principal.userId);
  // Fail closed, before reading the body, touching ai_responses or calling the model.
  await requirePro(principal);
  if (!deepseekConfigured()) throw aiUnavailable();
  const body = CoachRequestSchema.parse(await readJsonBody(request));
  const stream = await startCoachTurn(getCoachDeps(), principal.userId, body, request.signal);
  return new Response(stream, {
    headers: { 'content-type': NDJSON_CONTENT_TYPE, 'cache-control': 'no-store', 'x-accel-buffering': 'no' },
  });
});
