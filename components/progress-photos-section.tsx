'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { listPhotos } from '@/lib/repositories/progress-photos';
import { ProgressPhotoUpload } from '@/components/progress-photo-upload';
import { ProgressGallery } from '@/components/progress-gallery';
import { ProgressCompare } from '@/components/progress-compare';

export function ProgressPhotosSection() {
  const fotos = useLiveQuery(() => listPhotos(), []);

  return (
    <section className="space-y-3">
      <h2 className="label-mono text-[11px] text-muted-foreground">Fotos de progreso</h2>
      <ProgressPhotoUpload />
      {fotos === undefined ? (
        <p className="text-muted-foreground">Cargando…</p>
      ) : fotos.length === 0 ? (
        <p className="text-muted-foreground">Aún no has subido fotos. Añade la primera arriba.</p>
      ) : (
        <>
          <ProgressGallery fotos={fotos} />
          <ProgressCompare fotos={fotos} />
        </>
      )}
    </section>
  );
}
