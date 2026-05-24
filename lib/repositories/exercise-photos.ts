import { db } from '@/lib/db/database';
import type { ExercisePhoto } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

async function activaDe(exerciseId: string): Promise<ExercisePhoto | undefined> {
  const all = await db.exercisePhotos.where('exerciseId').equals(exerciseId).toArray();
  return activo(all)[0];
}

/** Crea o reemplaza la foto del ejercicio. Devuelve el `key` anterior si lo había (para borrarlo de R2). */
export async function setPhoto(exerciseId: string, input: { url: string; key: string }): Promise<string | undefined> {
  const existente = await activaDe(exerciseId);
  if (existente) {
    const prevKey = existente.key;
    await db.exercisePhotos.update(existente.id, { url: input.url, key: input.key, updatedAt: now() });
    return prevKey;
  }
  const foto: ExercisePhoto = {
    id: crypto.randomUUID(),
    userId: null,
    exerciseId,
    url: input.url,
    key: input.key,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.exercisePhotos.put(foto);
  return undefined;
}

/** Borra (soft-delete) la foto del ejercicio. Devuelve el `key` para borrarlo de R2. */
export async function removePhoto(exerciseId: string): Promise<string | undefined> {
  const existente = await activaDe(exerciseId);
  if (!existente) return undefined;
  await db.exercisePhotos.update(existente.id, { deletedAt: now(), updatedAt: now() });
  return existente.key;
}

export function getPhoto(exerciseId: string): Promise<ExercisePhoto | undefined> {
  return activaDe(exerciseId);
}

export async function getPhotosMap(): Promise<Map<string, ExercisePhoto>> {
  const map = new Map<string, ExercisePhoto>();
  for (const p of activo(await db.exercisePhotos.toArray())) map.set(p.exerciseId, p);
  return map;
}
