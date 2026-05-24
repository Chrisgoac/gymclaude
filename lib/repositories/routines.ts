import { db } from '@/lib/db/database';
import type { Routine, RoutineExercise } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

export async function createRoutine(input: { nombre: string; descripcion?: string }): Promise<Routine> {
  const routine: Routine = {
    id: crypto.randomUUID(),
    userId: null,
    nombre: input.nombre,
    descripcion: input.descripcion,
    archivada: false,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.routines.put(routine);
  return routine;
}

export function getRoutine(id: string): Promise<Routine | undefined> {
  return db.routines.get(id);
}

export async function listRoutines(): Promise<Routine[]> {
  const all = await db.routines.toArray();
  return activo(all).sort((a, b) => a.nombre.localeCompare(b.nombre, 'es', { sensitivity: 'base' }));
}

export async function updateRoutine(
  id: string,
  changes: Partial<Pick<Routine, 'nombre' | 'descripcion' | 'archivada'>>,
): Promise<void> {
  await db.routines.update(id, { ...changes, updatedAt: now() });
}

export async function softDeleteRoutine(id: string): Promise<void> {
  const ts = now();
  await db.transaction('rw', db.routines, db.routineExercises, async () => {
    await db.routines.update(id, { deletedAt: ts, updatedAt: ts });
    const res = activo(await db.routineExercises.where('routineId').equals(id).toArray());
    for (const re of res) await db.routineExercises.update(re.id, { deletedAt: ts, updatedAt: ts });
  });
}

export async function addExerciseToRoutine(
  routineId: string,
  input: {
    exerciseId: string;
    seriesObjetivo?: number;
    repsObjetivo?: number;
    descansoSegundos?: number;
    notas?: string;
  },
): Promise<RoutineExercise> {
  const existentes = activo(await db.routineExercises.where('routineId').equals(routineId).toArray());
  const re: RoutineExercise = {
    id: crypto.randomUUID(),
    routineId,
    exerciseId: input.exerciseId,
    orden: existentes.length,
    seriesObjetivo: input.seriesObjetivo,
    repsObjetivo: input.repsObjetivo,
    descansoSegundos: input.descansoSegundos,
    notas: input.notas,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.routineExercises.put(re);
  return re;
}

export async function listRoutineExercises(routineId: string): Promise<RoutineExercise[]> {
  const all = await db.routineExercises.where('routineId').equals(routineId).toArray();
  return activo(all).sort((a, b) => a.orden - b.orden);
}

export async function updateRoutineExercise(
  id: string,
  changes: Partial<Pick<RoutineExercise, 'seriesObjetivo' | 'repsObjetivo' | 'descansoSegundos' | 'notas'>>,
): Promise<void> {
  await db.routineExercises.update(id, { ...changes, updatedAt: now() });
}

export async function softDeleteRoutineExercise(id: string): Promise<void> {
  const ts = now();
  await db.routineExercises.update(id, { deletedAt: ts, updatedAt: ts });
}
