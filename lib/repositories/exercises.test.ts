import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { createExercise, listExercises, getExercise, updateExercise, softDeleteExercise } from '@/lib/repositories/exercises';

describe('repositorio de ejercicios', () => {
  beforeEach(async () => { await db.exercises.clear(); });

  it('crea un ejercicio con id, esPersonalizado=true y sin tombstone', async () => {
    const ex = await createExercise({ nombre: 'Curl martillo', grupoMuscular: 'biceps', equipamiento: 'mancuerna', tipo: 'aislamiento' });
    expect(ex.id).toBeTruthy();
    expect(ex.esPersonalizado).toBe(true);
    expect(ex.deletedAt).toBeNull();
    expect(await getExercise(ex.id)).toMatchObject({ nombre: 'Curl martillo' });
  });

  it('lista solo los ejercicios no borrados, ordenados por nombre', async () => {
    await createExercise({ nombre: 'Zancada', grupoMuscular: 'cuadriceps', equipamiento: 'mancuerna', tipo: 'compuesto' });
    await createExercise({ nombre: 'Aperturas', grupoMuscular: 'pecho', equipamiento: 'polea', tipo: 'aislamiento' });
    const list = await listExercises();
    expect(list.map((e) => e.nombre)).toEqual(['Aperturas', 'Zancada']);
  });

  it('ordena con criterio español ignorando mayúsculas y excluye los borrados', async () => {
    await createExercise({ nombre: 'zancada', grupoMuscular: 'cuadriceps', equipamiento: 'mancuerna', tipo: 'compuesto' });
    await createExercise({ nombre: 'Aperturas', grupoMuscular: 'pecho', equipamiento: 'polea', tipo: 'aislamiento' });
    const borrado = await createExercise({ nombre: 'Banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto' });
    await softDeleteExercise(borrado.id);
    const list = await listExercises();
    expect(list.map((e) => e.nombre)).toEqual(['Aperturas', 'zancada']);
  });

  it('actualiza un ejercicio y refresca updatedAt', async () => {
    const ex = await createExercise({ nombre: 'Sentadilla', grupoMuscular: 'cuadriceps', equipamiento: 'barra', tipo: 'compuesto' });
    const before = ex.updatedAt;
    await new Promise((r) => setTimeout(r, 2));
    await updateExercise(ex.id, { notas: 'Profundidad completa' });
    const after = await getExercise(ex.id);
    expect(after?.notas).toBe('Profundidad completa');
    expect(after!.updatedAt).toBeGreaterThan(before);
  });

  it('borra de forma lógica (tombstone) y deja de listarse', async () => {
    const ex = await createExercise({ nombre: 'Peso muerto', grupoMuscular: 'espalda', equipamiento: 'barra', tipo: 'compuesto' });
    await softDeleteExercise(ex.id);
    const stored = await getExercise(ex.id);
    expect(stored?.deletedAt).not.toBeNull();
    expect(await listExercises()).toHaveLength(0);
  });

  it('persiste incrementoKg al actualizar el ejercicio', async () => {
    const ex = await createExercise({ nombre: 'Press banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto' });
    await updateExercise(ex.id, { incrementoKg: 1.25 });
    const leido = await getExercise(ex.id);
    expect(leido?.incrementoKg).toBe(1.25);
  });
});
