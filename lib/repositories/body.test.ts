import { describe, it, expect, beforeEach } from 'vitest';
import 'fake-indexeddb/auto';
import { db } from '@/lib/db/database';
import { addMetric, listMetrics, listTipos, deleteMetric, listAllMetrics } from '@/lib/repositories/body';

beforeEach(async () => {
  await db.bodyMetrics.clear();
});

describe('body repo', () => {
  it('addMetric + listMetrics devuelve la serie de ese tipo en orden cronológico', async () => {
    await addMetric('peso', 78.4, 2000);
    await addMetric('peso', 78.0, 1000);
    const serie = await listMetrics('peso');
    expect(serie.map((m) => [m.fecha, m.valor])).toEqual([[1000, 78.0], [2000, 78.4]]);
  });
  it('listMetrics filtra por tipo', async () => {
    await addMetric('peso', 78, 1);
    await addMetric('cintura', 84, 1);
    expect((await listMetrics('peso')).map((m) => m.valor)).toEqual([78]);
    expect((await listMetrics('cintura')).map((m) => m.valor)).toEqual([84]);
  });
  it('addMetric genera id, marca sync y fecha por defecto = ahora', async () => {
    const antes = Date.now();
    const m = await addMetric('peso', 80);
    expect(m.id).toBeTruthy();
    expect(m.userId).toBeNull();
    expect(m.deletedAt).toBeNull();
    expect(m.fecha).toBeGreaterThanOrEqual(antes);
  });
  it('listTipos devuelve solo tipos con entradas activas', async () => {
    await addMetric('peso', 80, 1);
    await addMetric('cintura', 84, 1);
    const m = await addMetric('biceps', 38, 1);
    await deleteMetric(m.id);
    expect((await listTipos()).sort()).toEqual(['cintura', 'peso']);
  });
  it('deleteMetric hace tombstone (no aparece en listMetrics, fila sigue)', async () => {
    const m = await addMetric('peso', 80, 1);
    await deleteMetric(m.id);
    expect(await listMetrics('peso')).toHaveLength(0);
    expect(await db.bodyMetrics.count()).toBe(1);
  });
  it('listAllMetrics devuelve todas las activas ordenadas por fecha asc', async () => {
    await db.bodyMetrics.clear();
    await addMetric('peso', 80, 2000);
    await addMetric('cintura', 84, 1000);
    const todas = await listAllMetrics();
    expect(todas.map((m) => m.fecha)).toEqual([1000, 2000]);
    expect(todas).toHaveLength(2);
  });
});
