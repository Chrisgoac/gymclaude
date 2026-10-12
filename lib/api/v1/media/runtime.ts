import { getPoolDb } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import { r2MediaStorage, r2PrivateConfigured } from '@/lib/r2/client';
import { createMediaService, type MediaService } from './service';

/**
 * Servicio de medios de producción (Postgres + bucket R2 privado). Sin `R2_PRIVATE_BUCKET` (o sin
 * credenciales) falla cerrado con 503 MEDIA_UNAVAILABLE: nunca se escribe en el bucket público.
 * Los tests de ruta mockean este módulo.
 */
export function getMediaService(): MediaService {
  if (!r2PrivateConfigured()) {
    throw new ApiError(503, 'MEDIA_UNAVAILABLE', 'Private media storage is not configured', true);
  }
  return createMediaService({ db: getPoolDb(), storage: r2MediaStorage });
}
