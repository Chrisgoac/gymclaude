import { db } from '@/lib/db/database';
import type { Achievement } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

/** Logros desbloqueados (activos). */
export async function listAchievements(): Promise<Achievement[]> {
  return activo(await db.achievements.toArray());
}

/** Desbloquea un hito por su clave. Idempotente: si ya está activo, no hace nada. */
export async function unlockAchievement(clave: string): Promise<void> {
  const existentes = activo(await db.achievements.where('clave').equals(clave).toArray());
  if (existentes.length > 0) return;
  const ts = now();
  const a: Achievement = {
    id: crypto.randomUUID(),
    userId: null,
    clave,
    fechaDesbloqueo: ts,
    updatedAt: ts,
    deletedAt: null,
  };
  await db.achievements.put(a);
}

/** Mapa clave → Achievement (activos). */
export async function getAchievementMap(): Promise<Map<string, Achievement>> {
  const todos = await listAchievements();
  return new Map(todos.map((a) => [a.clave, a]));
}
