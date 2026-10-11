import { getPoolDb } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import { r2MediaStorage, r2PrivateConfigured } from '@/lib/r2/client';
import { createMediaService, type MediaService } from './service';

/** Servicio de medios de producción (Postgres + R2 privado). Los tests de ruta mockean este módulo. */
export function getMediaService(): MediaService {
  if (!r2PrivateConfigured()) {
    throw new ApiError(503, 'MEDIA_STORAGE_UNAVAILABLE', 'Media storage unavailable', true);
  }
  return createMediaService({ db: getPoolDb(), storage: r2MediaStorage });
}
