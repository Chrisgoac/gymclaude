import { db } from '@/lib/db/database';
import type { Exercise } from '@/lib/db/types';

export type NewExerciseInput = Pick<Exercise, 'nombre' | 'grupoMuscular' | 'equipamiento' | 'tipo'> &
  Partial<Pick<Exercise, 'videoUrl' | 'notas' | 'incrementoKg'>>;

export type ExerciseChanges = Partial<
  Pick<Exercise, 'nombre' | 'grupoMuscular' | 'equipamiento' | 'tipo' | 'videoUrl' | 'notas' | 'incrementoKg'>
>;

export async function createExercise(input: NewExerciseInput): Promise<Exercise> {
  const exercise: Exercise = {
    ...input,
    id: crypto.randomUUID(),
    userId: null,
    esPersonalizado: true,
    updatedAt: Date.now(),
    deletedAt: null,
  };
  await db.exercises.put(exercise);
  return exercise;
}

export async function getExercise(id: string): Promise<Exercise | undefined> {
  return db.exercises.get(id);
}

export async function listExercises(): Promise<Exercise[]> {
  const all = await db.exercises.toArray();
  return all
    .filter((e) => e.deletedAt === null)
    .sort((a, b) => a.nombre.localeCompare(b.nombre, 'es', { sensitivity: 'base' }));
}

export async function updateExercise(id: string, changes: ExerciseChanges): Promise<void> {
  await db.exercises.update(id, { ...changes, updatedAt: Date.now() });
}

export async function softDeleteExercise(id: string): Promise<void> {
  const now = Date.now();
  await db.exercises.update(id, { deletedAt: now, updatedAt: now });
}
