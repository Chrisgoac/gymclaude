import { enforceRateLimit } from '@/lib/api/v1/rate-limit';
import { withApiRoute } from '@/lib/api/v1/route';
import { mediaIdFromUrl } from '@/lib/api/v1/media/http';
import { getMediaService } from '@/lib/api/v1/media/runtime';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

// Android (bearer): devuelve la concesión JSON con la URL firmada breve. Nunca se cachea.
export const GET = withApiRoute(async (request, principal) => {
  await enforceRateLimit('media', principal.userId);
  const grant = await getMediaService().readGrant(principal.userId, mediaIdFromUrl(request));
  return Response.json(grant, { headers: { 'cache-control': 'no-store' } });
});
