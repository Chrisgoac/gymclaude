import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import {
  createRoutine, listRoutines, getRoutine, updateRoutine, softDeleteRoutine,
  addDay, listDays, updateDay, softDeleteDay,
  addExerciseToDay, listDayExercises, updateRoutineExercise, softDeleteRoutineExercise,
} from '@/lib/repositories/routines';

beforeEach(async () => {
  await db.routines.clear();
  await db.routineDays.clear();
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

  it('actualiza nombre/descripcion y refresca updatedAt', async () => {
    const r = await createRoutine({ nombre: 'A' });
    await new Promise((res) => setTimeout(res, 2));
    await updateRoutine(r.id, { nombre: 'B', descripcion: 'mi plan' });
    const after = await getRoutine(r.id);
    expect(after).toMatchObject({ nombre: 'B', descripcion: 'mi plan' });
    expect(after!.updatedAt).toBeGreaterThan(r.updatedAt);
  });

  it('borra en cascada la rutina, sus días y los ejercicios de esos días', async () => {
    const r = await createRoutine({ nombre: 'PPL' });
    const d = await addDay(r.id, { nombre: 'Empuje' });
    const re = await addExerciseToDay(d.id, { exerciseId: 'seed-press-banca', seriesObjetivo: 3, repsObjetivo: 8 });
    await softDeleteRoutine(r.id);
    expect(await listRoutines()).toHaveLength(0);
    expect(await listDays(r.id)).toHaveLength(0);
    expect(await listDayExercises(d.id)).toHaveLength(0);
    expect((await getRoutine(r.id))!.deletedAt).not.toBeNull();
    expect((await db.routineExercises.get(re.id))!.deletedAt).not.toBeNull();
  });
});

describe('días', () => {
  it('añade días con orden incremental y los lista por orden', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const d1 = await addDay(r.id, { nombre: 'Día 1' });
    const d2 = await addDay(r.id, { nombre: 'Día 2' });
    expect(d1.orden).toBe(0);
    expect(d2.orden).toBe(1);
    expect((await listDays(r.id)).map((d) => d.nombre)).toEqual(['Día 1', 'Día 2']);
  });

  it('borra un día en cascada con sus ejercicios', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const d = await addDay(r.id, { nombre: 'D' });
    await addExerciseToDay(d.id, { exerciseId: 'seed-sentadilla' });
    await softDeleteDay(d.id);
    expect(await listDays(r.id)).toHaveLength(0);
    expect(await listDayExercises(d.id)).toHaveLength(0);
  });
});

describe('ejercicios del día', () => {
  it('añade, actualiza objetivos y borra', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const d = await addDay(r.id, { nombre: 'D' });
    const re = await addExerciseToDay(d.id, { exerciseId: 'seed-press-banca' });
    expect(re.orden).toBe(0);
    await updateRoutineExercise(re.id, { seriesObjetivo: 4, repsObjetivo: 10, descansoSegundos: 120 });
    expect(await db.routineExercises.get(re.id)).toMatchObject({ seriesObjetivo: 4, repsObjetivo: 10, descansoSegundos: 120 });
    await softDeleteRoutineExercise(re.id);
    expect(await listDayExercises(d.id)).toHaveLength(0);
  });
});
