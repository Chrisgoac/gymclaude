import { getPoolDb } from '@/db/pool';
import { enforceAiBudget } from '@/lib/api/v1/ai/budget';
import { AiResponseIdSchema, ReportResponseRequestSchema } from '@/lib/api/v1/ai/contracts';
import { readJsonBody } from '@/lib/api/v1/ai/http';
import { reportAiResponse, responseNotFound } from '@/lib/api/v1/ai/store';
import { withApiRoute } from '@/lib/api/v1/route';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

/** `/api/v1/ai/responses/<id>/report` → id. A malformed id is a 404, like an unknown one. */
function responseIdFromUrl(request: Request): string {
  const segments = new URL(request.url).pathname.split('/').filter(Boolean);
  let raw = segments[segments.length - 2] ?? '';
  try {
    raw = decodeURIComponent(raw);
  } catch {
    /* rejected below */
  }
  const parsed = AiResponseIdSchema.safeParse(raw);
  if (!parsed.success) throw responseNotFound();
  return parsed.data;
}

// Reporting needs no Pro: any signed-in user may report a response they own (also after Pro ends).
export const POST = withApiRoute(async (request, principal) => {
  await enforceAiBudget(principal.userId);
  const responseId = responseIdFromUrl(request);
  const body = ReportResponseRequestSchema.parse(await readJsonBody(request, 8 * 1024));
  const { created } = await reportAiResponse(getPoolDb(), principal.userId, responseId, body, Date.now());
  return Response.json({ responseId, created }, { status: created ? 201 : 200, headers: { 'cache-control': 'no-store' } });
});
