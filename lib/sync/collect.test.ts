import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { createExercise } from '@/lib/repositories/exercises';
import { createRoutine } from '@/lib/repositories/routines';
import { collectDirty } from '@/lib/sync/collect';

beforeEach(async () => {
  await Promise.all([db.exercises.clear(), db.routines.clear(), db.gyms.clear()]);
});

describe('collectDirty', () => {
  it('recoge registros con updatedAt > marca de agua', async () => {
    await createRoutine({ nombre: 'Vieja' });
    const changes = await collectDirty(0);
    const rutinas = changes.find((c) => c.table === 'routines');
    expect(rutinas?.records).toHaveLength(1);
  });

  it('excluye lo anterior a la marca de agua', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const changes = await collectDirty(r.updatedAt);
    expect(changes.find((c) => c.table === 'routines')).toBeUndefined();
  });

  it('sincroniza los gimnasios', async () => {
    await db.gyms.clear();
    await db.gyms.put({
      id: 'g1', userId: null, nombre: 'Gold\'s', orden: 0, archivada: false,
      updatedAt: Date.now(), deletedAt: null,
    });
    const changes = await collectDirty(0);
    expect(changes.find((c) => c.table === 'gyms')?.records).toHaveLength(1);
  });

  it('sólo sincroniza ejercicios personalizados, no los del catálogo (seed)', async () => {
    await db.exercises.put({
      id: 'seed-x', userId: null, nombre: 'Seed', grupoMuscular: 'pecho',
      equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: Date.now(), deletedAt: null,
    });
    await createExercise({ nombre: 'Mío', grupoMuscular: 'biceps', equipamiento: 'mancuerna', tipo: 'aislamiento' });
    const changes = await collectDirty(0);
    const ej = changes.find((c) => c.table === 'exercises');
    expect(ej?.records.map((r) => (r as unknown as { nombre: string }).nombre)).toEqual(['Mío']);
  });
});
