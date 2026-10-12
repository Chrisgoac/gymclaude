import { enforceAiBudget } from '@/lib/api/v1/ai/budget';
import { MesocycleRequestSchema } from '@/lib/api/v1/ai/contracts';
import { requirePro } from '@/lib/api/v1/ai/guard';
import { aiUnavailable, readJsonBody } from '@/lib/api/v1/ai/http';
import { getMesocycleDeps } from '@/lib/api/v1/ai/runtime';
import { withApiRoute } from '@/lib/api/v1/route';
import { deepseekConfigured } from '@/lib/coach-model';
import { generateMesocycleDraft } from '@/lib/mesocycle-draft';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';
// The model call itself is cut at MESO_TIMEOUT_MS (90 s), inside this budget.
export const maxDuration = 120;

/** Body cap: a 400-entry catalog of ≤ 200-char names plus the 32 KiB snapshot, with JSON escaping. */
const MAX_MESO_BODY_BYTES = 192 * 1024;

// Returns a validated DRAFT; nothing of the user's data is written (the client saves what it reviewed).
export const POST = withApiRoute(async (request, principal) => {
  await enforceAiBudget(principal.userId);
  // Fail closed, before reading the body, touching ai_responses or calling the model.
  await requirePro(principal);
  if (!deepseekConfigured()) throw aiUnavailable();
  const body = MesocycleRequestSchema.parse(await readJsonBody(request, MAX_MESO_BODY_BYTES));
  const result = await generateMesocycleDraft(getMesocycleDeps(), principal.userId, body, request.signal);
  return Response.json(result, { headers: { 'cache-control': 'no-store' } });
});
