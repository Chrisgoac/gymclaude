import { enforceRateLimit } from '@/lib/api/v1/rate-limit';
import { withApiRoute } from '@/lib/api/v1/route';
import { mediaIdFromUrl } from '@/lib/api/v1/media/http';
import { getMediaService } from '@/lib/api/v1/media/runtime';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

export const DELETE = withApiRoute(async (request, principal) => {
  await enforceRateLimit('media', principal.userId);
  const result = await getMediaService().delete(principal.userId, mediaIdFromUrl(request));
  return Response.json(result, { headers: { 'cache-control': 'no-store' } });
});
