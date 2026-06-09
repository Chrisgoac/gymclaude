import { db } from '@/lib/db/database';
import type { BodyMetric } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

/** Entradas activas de un tipo, en orden cronológico ascendente. */
export async function listMetrics(tipo: string): Promise<BodyMetric[]> {
  const all = activo(await db.bodyMetrics.where('tipo').equals(tipo).toArray());
  return all.sort((a, b) => a.fecha - b.fecha);
}

/** Tipos de métrica con al menos una entrada activa. */
export async function listTipos(): Promise<string[]> {
  const all = activo(await db.bodyMetrics.toArray());
  return [...new Set(all.map((m) => m.tipo))];
}

/** Añade una medición. `fecha` por defecto = ahora. */
export async function addMetric(tipo: string, valor: number, fecha?: number): Promise<BodyMetric> {
  const ts = now();
  const m: BodyMetric = {
    id: crypto.randomUUID(),
    userId: null,
    tipo,
    valor,
    fecha: fecha ?? ts,
    updatedAt: ts,
    deletedAt: null,
  };
  await db.bodyMetrics.put(m);
  return m;
}

/** Borra (tombstone) una medición. */
export async function deleteMetric(id: string): Promise<void> {
  const ts = now();
  await db.bodyMetrics.update(id, { deletedAt: ts, updatedAt: ts });
}
