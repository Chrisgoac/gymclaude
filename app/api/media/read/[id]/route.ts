import { withApiRoute } from '@/lib/api/v1/route';
import { mediaIdFromUrl } from '@/lib/api/v1/media/http';
import { getMediaService } from '@/lib/api/v1/media/runtime';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

// PWA (`<img src>`, cookie de Clerk): 302 a la URL firmada breve. La redirección no se cachea ni
// se comparte: la URL firmada solo viaja en `Location`. Sin límite 'media': una galería carga
// decenas de imágenes y firmar es local; la ruta exige sesión y propiedad.
export const GET = withApiRoute(async (request, principal) => {
  const grant = await getMediaService().readGrant(principal.userId, mediaIdFromUrl(request));
  return new Response(null, {
    status: 302,
    headers: {
      location: grant.readUrl,
      'cache-control': 'private, no-store',
      'referrer-policy': 'no-referrer',
      'content-type': 'text/plain; charset=utf-8',
    },
  });
});
