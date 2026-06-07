import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import {
  createRoutine, listRoutines, getRoutine, updateRoutine, softDeleteRoutine,
  reorderRoutines,
  addExerciseToRoutine, listRoutineExercises, updateRoutineExercise, softDeleteRoutineExercise,
} from '@/lib/repositories/routines';

beforeEach(async () => {
  await db.routines.clear();
  await db.routineExercises.clear();
});

describe('rutinas', () => {
  it('crea una rutina activa y la lista', async () => {
    const r = await createRoutine({ nombre: 'Full Body' });
    expect(r.id).toBeTruthy();
    expect(r.archivada).toBe(false);
    expect(r.deletedAt).toBeNull();
    expect((await listRoutines()).map((x) => x.nombre)).toEqual(['Full Body']);
    expect(await getRoutine(r.id)).toMatchObject({ nombre: 'Full Body' });
  });

  it('asigna orden incremental al crear y lista por orden', async () => {
    const a = await createRoutine({ nombre: 'Empuje' });
    const b = await createRoutine({ nombre: 'Tirón' });
    const c = await createRoutine({ nombre: 'Pierna' });
    expect(a.orden).toBe(0);
    expect(b.orden).toBe(1);
    expect(c.orden).toBe(2);
    expect((await listRoutines()).map((r) => r.nombre)).toEqual(['Empuje', 'Tirón', 'Pierna']);
  });

  it('reordena reescribiendo orden y refrescando updatedAt', async () => {
    const a = await createRoutine({ nombre: 'A' });
    const b = await createRoutine({ nombre: 'B' });
    const c = await createRoutine({ nombre: 'C' });
    await new Promise((res) => setTimeout(res, 2));
    await reorderRoutines([c.id, a.id, b.id]);
    expect((await listRoutines()).map((r) => r.nombre)).toEqual(['C', 'A', 'B']);
    expect((await getRoutine(c.id))!.orden).toBe(0);
    expect((await getRoutine(c.id))!.updatedAt).toBeGreaterThan(c.updatedAt);
  });

  it('actualiza nombre/descripcion y refresca updatedAt', async () => {
    const r = await createRoutine({ nombre: 'A' });
    await new Promise((res) => setTimeout(res, 2));
    await updateRoutine(r.id, { nombre: 'B', descripcion: 'mi plan' });
    const after = await getRoutine(r.id);
    expect(after).toMatchObject({ nombre: 'B', descripcion: 'mi plan' });
    expect(after!.updatedAt).toBeGreaterThan(r.updatedAt);
  });

  it('borra en cascada la rutina y sus ejercicios', async () => {
    const r = await createRoutine({ nombre: 'PPL' });
    const re = await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca', seriesObjetivo: 3, repsObjetivo: 8 });
    await softDeleteRoutine(r.id);
    expect(await listRoutines()).toHaveLength(0);
    expect(await listRoutineExercises(r.id)).toHaveLength(0);
    expect((await getRoutine(r.id))!.deletedAt).not.toBeNull();
    expect((await db.routineExercises.get(re.id))!.deletedAt).not.toBeNull();
  });
});

describe('ejercicios de la rutina', () => {
  it('añade con orden incremental y lista por orden', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const e1 = await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
    const e2 = await addExerciseToRoutine(r.id, { exerciseId: 'seed-sentadilla' });
    expect(e1.routineId).toBe(r.id);
    expect(e1.orden).toBe(0);
    expect(e2.orden).toBe(1);
    expect((await listRoutineExercises(r.id)).map((re) => re.exerciseId))
      .toEqual(['seed-press-banca', 'seed-sentadilla']);
  });

  it('actualiza objetivos y borra', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const re = await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
    await updateRoutineExercise(re.id, { seriesObjetivo: 4, repsObjetivo: 10, descansoSegundos: 120 });
    expect(await db.routineExercises.get(re.id)).toMatchObject({ seriesObjetivo: 4, repsObjetivo: 10, descansoSegundos: 120 });
    await softDeleteRoutineExercise(re.id);
    expect(await listRoutineExercises(r.id)).toHaveLength(0);
  });
});
