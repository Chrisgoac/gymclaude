import { db } from '@/lib/db/database';
import type { Mesocycle, SemanaPlan } from '@/lib/db/types';

const DIA = 86400000;
const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

export async function createMesocycle(input: {
  nombre: string;
  objetivo: string;
  semanas: number;
  diasPorSemana: number;
  notas: string | null;
  progresion: SemanaPlan[];
  fechaInicio: number;
}): Promise<Mesocycle> {
  const ts = now();
  const m: Mesocycle = { id: crypto.randomUUID(), userId: null, ...input, updatedAt: ts, deletedAt: null };
  await db.mesocycles.put(m);
  return m;
}

export async function getMesocycle(id: string): Promise<Mesocycle | undefined> {
  const m = await db.mesocycles.get(id);
  return m && m.deletedAt === null ? m : undefined;
}

export async function listMesocycles(): Promise<Mesocycle[]> {
  return activo(await db.mesocycles.toArray()).sort((a, b) => b.fechaInicio - a.fechaInicio);
}

export async function deleteMesocycle(id: string): Promise<void> {
  const ts = now();
  await db.mesocycles.update(id, { deletedAt: ts, updatedAt: ts });
}

/** Número de semana 1..semanas calculado desde fechaInicio, acotado. */
export function semanaActual(meso: Mesocycle, ahora: number): number {
  const transcurridas = Math.floor((ahora - meso.fechaInicio) / (7 * DIA));
  return Math.min(meso.semanas, Math.max(1, transcurridas + 1));
}
