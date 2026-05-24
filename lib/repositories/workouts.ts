import { db } from '@/lib/db/database';
import type { WorkoutSession, LoggedExercise, LoggedSet } from '@/lib/db/types';
import { listDayExercises } from '@/lib/repositories/routines';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

export async function startSession(input: { routineDayId?: string; gymId?: string | null }): Promise<WorkoutSession> {
  const ts = now();
  const session: WorkoutSession = {
    id: crypto.randomUUID(),
    userId: null,
    routineDayId: input.routineDayId,
    gymId: input.gymId ?? null,
    fecha: ts,
    updatedAt: ts,
    deletedAt: null,
  };
  await db.workoutSessions.put(session);
  if (input.routineDayId) {
    const dayExercises = await listDayExercises(input.routineDayId);
    for (const re of dayExercises) {
      await addLoggedExercise(session.id, re.exerciseId);
    }
  }
  return session;
}

export function getSession(id: string): Promise<WorkoutSession | undefined> {
  return db.workoutSessions.get(id);
}

export async function listSessions(): Promise<WorkoutSession[]> {
  const all = await db.workoutSessions.toArray();
  return activo(all).sort((a, b) => b.fecha - a.fecha);
}

export async function finishSession(id: string, input: { notas?: string }): Promise<void> {
  const session = await db.workoutSessions.get(id);
  if (!session) return;
  const duracionSegundos = Math.round((now() - session.fecha) / 1000);
  await db.workoutSessions.update(id, { duracionSegundos, notas: input.notas, updatedAt: now() });
}

export async function softDeleteSession(id: string): Promise<void> {
  const ts = now();
  await db.transaction('rw', db.workoutSessions, db.loggedExercises, db.loggedSets, async () => {
    await db.workoutSessions.update(id, { deletedAt: ts, updatedAt: ts });
    const les = activo(await db.loggedExercises.where('sessionId').equals(id).toArray());
    for (const le of les) {
      await db.loggedExercises.update(le.id, { deletedAt: ts, updatedAt: ts });
      const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
      for (const set of sets) await db.loggedSets.update(set.id, { deletedAt: ts, updatedAt: ts });
    }
  });
}

export async function addLoggedExercise(sessionId: string, exerciseId: string): Promise<LoggedExercise> {
  const existentes = activo(await db.loggedExercises.where('sessionId').equals(sessionId).toArray());
  const le: LoggedExercise = {
    id: crypto.randomUUID(),
    sessionId,
    exerciseId,
    orden: existentes.length,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.loggedExercises.put(le);
  return le;
}

export async function listSessionExercises(sessionId: string): Promise<LoggedExercise[]> {
  const all = await db.loggedExercises.where('sessionId').equals(sessionId).toArray();
  return activo(all).sort((a, b) => a.orden - b.orden);
}

export async function softDeleteLoggedExercise(id: string): Promise<void> {
  const ts = now();
  await db.transaction('rw', db.loggedExercises, db.loggedSets, async () => {
    await db.loggedExercises.update(id, { deletedAt: ts, updatedAt: ts });
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(id).toArray());
    for (const set of sets) await db.loggedSets.update(set.id, { deletedAt: ts, updatedAt: ts });
  });
}

export async function addSet(loggedExerciseId: string, input: { peso: number; reps: number; esCalentamiento?: boolean }): Promise<LoggedSet> {
  const existentes = activo(await db.loggedSets.where('loggedExerciseId').equals(loggedExerciseId).toArray());
  const set: LoggedSet = {
    id: crypto.randomUUID(),
    loggedExerciseId,
    orden: existentes.length,
    peso: input.peso,
    reps: input.reps,
    esCalentamiento: input.esCalentamiento,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.loggedSets.put(set);
  return set;
}

export async function updateSet(id: string, changes: Partial<Pick<LoggedSet, 'peso' | 'reps' | 'esCalentamiento'>>): Promise<void> {
  await db.loggedSets.update(id, { ...changes, updatedAt: now() });
}

export async function softDeleteSet(id: string): Promise<void> {
  const ts = now();
  await db.loggedSets.update(id, { deletedAt: ts, updatedAt: ts });
}

export async function listExerciseSets(loggedExerciseId: string): Promise<LoggedSet[]> {
  const all = await db.loggedSets.where('loggedExerciseId').equals(loggedExerciseId).toArray();
  return activo(all).sort((a, b) => a.orden - b.orden);
}

export async function getLastSet(
  exerciseId: string,
  excludeSessionId?: string,
  gymId?: string | null,
): Promise<LoggedSet | undefined> {
  const les = activo(await db.loggedExercises.where('exerciseId').equals(exerciseId).toArray())
    .filter((le) => le.sessionId !== excludeSessionId);
  if (les.length === 0) return undefined;
  const sessionIds = [...new Set(les.map((le) => le.sessionId))];
  const sessions = await db.workoutSessions.bulkGet(sessionIds);
  const fechaBySession = new Map<string, number>();
  const gymBySession = new Map<string, string | null | undefined>();
  for (const s of sessions) if (s) {
    fechaBySession.set(s.id, s.fecha);
    gymBySession.set(s.id, s.gymId ?? null);
  }
  const candidatos = gymId == null
    ? les
    : les.filter((le) => gymBySession.get(le.sessionId) === gymId);
  candidatos.sort((a, b) => (fechaBySession.get(b.sessionId) ?? 0) - (fechaBySession.get(a.sessionId) ?? 0));
  for (const le of candidatos) {
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    if (sets.length > 0) {
      sets.sort((a, b) => b.orden - a.orden);
      return sets[0];
    }
  }
  return undefined;
}

/** Nº de sesiones activas sin gimnasio asignado. */
export async function countSessionsWithoutGym(): Promise<number> {
  return activo(await db.workoutSessions.toArray()).filter((s) => !s.gymId).length;
}

/** Asigna gymId a todas las sesiones activas sin gimnasio. Devuelve cuántas tocó. */
export async function assignGymToSessionsWithoutGym(gymId: string): Promise<number> {
  const ts = now();
  const sinGym = activo(await db.workoutSessions.toArray()).filter((s) => !s.gymId);
  await db.transaction('rw', db.workoutSessions, async () => {
    for (const s of sinGym) {
      await db.workoutSessions.update(s.id, { gymId, updatedAt: ts });
    }
  });
  return sinGym.length;
}
