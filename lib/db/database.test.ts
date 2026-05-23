import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';

describe('GymLogDB', () => {
  beforeEach(async () => {
    await db.exercises.clear();
  });

  it('tiene la tabla exercises', () => {
    expect(db.exercises).toBeDefined();
  });

  it('persiste y recupera un ejercicio por id', async () => {
    await db.exercises.put({
      id: 'x1', userId: null, nombre: 'Press banca', grupoMuscular: 'pecho',
      equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false,
      updatedAt: 1, deletedAt: null,
    });
    const found = await db.exercises.get('x1');
    expect(found?.nombre).toBe('Press banca');
  });

  it('expone las tablas de rutinas (v2)', () => {
    expect(db.routines).toBeDefined();
    expect(db.routineDays).toBeDefined();
    expect(db.routineExercises).toBeDefined();
  });
});
