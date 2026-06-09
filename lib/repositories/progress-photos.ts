import { db } from '@/lib/db/database';
import type { ProgressPhoto, AnguloFoto } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

/** Fotos activas, orden fecha desc (más reciente primero). */
export async function listPhotos(): Promise<ProgressPhoto[]> {
  const all = activo(await db.progressPhotos.toArray());
  return all.sort((a, b) => b.fecha - a.fecha);
}

/** Crea una foto de progreso. La metadata se guarda aquí; el objeto ya está en R2. */
export async function addPhoto(input: {
  url: string;
  key: string;
  fecha: number;
  angulo: AnguloFoto;
  nota: string | null;
}): Promise<ProgressPhoto> {
  const ts = now();
  const foto: ProgressPhoto = {
    id: crypto.randomUUID(),
    userId: null,
    url: input.url,
    key: input.key,
    fecha: input.fecha,
    angulo: input.angulo,
    nota: input.nota,
    updatedAt: ts,
    deletedAt: null,
  };
  await db.progressPhotos.put(foto);
  return foto;
}

/** Borra (tombstone) una foto. Devuelve el `key` para limpiar el objeto en R2. */
export async function deletePhoto(id: string): Promise<string | undefined> {
  const foto = await db.progressPhotos.get(id);
  if (!foto) return undefined;
  const ts = now();
  await db.progressPhotos.update(id, { deletedAt: ts, updatedAt: ts });
  return foto.key;
}
