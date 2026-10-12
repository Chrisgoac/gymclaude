import { enforceRateLimit } from '@/lib/api/v1/rate-limit';
import { withApiRoute } from '@/lib/api/v1/route';
import { readMediaUpload } from '@/lib/api/v1/media/http';
import { getMediaService } from '@/lib/api/v1/media/runtime';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

export const POST = withApiRoute(async (request, principal) => {
  await enforceRateLimit('media', principal.userId);
  // Topes de tamaño y cancelación se aplican al leer el cuerpo, antes de cualquier subida a R2.
  const input = await readMediaUpload(request, 'exercise');
  const result = await getMediaService().upload(principal.userId, input);
  return Response.json(result, { status: 201, headers: { 'cache-control': 'no-store' } });
});
