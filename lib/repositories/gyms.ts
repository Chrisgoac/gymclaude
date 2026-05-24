import { db } from '@/lib/db/database';
import type { Gym } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

/** Gimnasios activos (no borrados, no archivados), ordenados. */
export async function listGyms(): Promise<Gym[]> {
  const all = activo(await db.gyms.toArray()).filter((g) => !g.archivada);
  return all.sort((a, b) => a.orden - b.orden);
}

export async function createGym(nombre: string): Promise<Gym> {
  const existentes = activo(await db.gyms.toArray());
  const gym: Gym = {
    id: crypto.randomUUID(),
    userId: null,
    nombre: nombre.trim(),
    orden: existentes.length,
    archivada: false,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.gyms.put(gym);
  return gym;
}

export async function renameGym(id: string, nombre: string): Promise<void> {
  await db.gyms.update(id, { nombre: nombre.trim(), updatedAt: now() });
}

export async function archiveGym(id: string, archivada: boolean): Promise<void> {
  await db.gyms.update(id, { archivada, updatedAt: now() });
}

export async function softDeleteGym(id: string): Promise<void> {
  await db.gyms.update(id, { deletedAt: now(), updatedAt: now() });
}

export async function reorderGyms(idsEnOrden: string[]): Promise<void> {
  const ts = now();
  await db.transaction('rw', db.gyms, async () => {
    for (let i = 0; i < idsEnOrden.length; i++) {
      await db.gyms.update(idsEnOrden[i], { orden: i, updatedAt: ts });
    }
  });
}

/** Mapa id→Gym incluyendo archivados (no borrados) para resolver nombres en la UI. */
export async function getGymsMap(): Promise<Map<string, Gym>> {
  const map = new Map<string, Gym>();
  for (const g of activo(await db.gyms.toArray())) map.set(g.id, g);
  return map;
}

/** Nombre a mostrar para un gymId (o "Sin gimnasio"). */
export function gymDisplayName(gymId: string | null | undefined, map: Map<string, Gym>): string {
  if (!gymId) return 'Sin gimnasio';
  return map.get(gymId)?.nombre ?? 'Sin gimnasio';
}
