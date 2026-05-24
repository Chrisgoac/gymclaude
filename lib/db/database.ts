import Dexie, { type Table } from 'dexie';
import type {
  Exercise, Routine, RoutineExercise,
  WorkoutSession, LoggedExercise, LoggedSet, SyncState, Gym,
} from './types';

export class GymLogDB extends Dexie {
  exercises!: Table<Exercise, string>;
  routines!: Table<Routine, string>;
  routineExercises!: Table<RoutineExercise, string>;
  workoutSessions!: Table<WorkoutSession, string>;
  loggedExercises!: Table<LoggedExercise, string>;
  loggedSets!: Table<LoggedSet, string>;
  syncState!: Table<SyncState, string>;
  gyms!: Table<Gym, string>;

  constructor() {
    super('gymlog');
    this.version(1).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
    });
    this.version(2).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineDayId, exerciseId, orden, deletedAt',
    });
    this.version(3).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineDayId, exerciseId, orden, deletedAt',
      workoutSessions: 'id, userId, routineDayId, fecha, deletedAt',
      loggedExercises: 'id, sessionId, exerciseId, orden, deletedAt',
      loggedSets: 'id, loggedExerciseId, orden, deletedAt',
    });
    this.version(4).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineDayId, exerciseId, orden, deletedAt',
      workoutSessions: 'id, userId, routineDayId, fecha, deletedAt',
      loggedExercises: 'id, sessionId, exerciseId, orden, deletedAt',
      loggedSets: 'id, loggedExerciseId, orden, deletedAt',
      syncState: 'key',
    });
    this.version(5).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineDayId, exerciseId, orden, deletedAt',
      workoutSessions: 'id, userId, routineDayId, gymId, fecha, deletedAt',
      loggedExercises: 'id, sessionId, exerciseId, orden, deletedAt',
      loggedSets: 'id, loggedExerciseId, orden, deletedAt',
      syncState: 'key',
      gyms: 'id, userId, nombre, deletedAt',
    });
    this.version(6).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineId, routineDayId, exerciseId, orden, deletedAt',
      workoutSessions: 'id, userId, routineDayId, gymId, fecha, deletedAt',
      loggedExercises: 'id, sessionId, exerciseId, orden, deletedAt',
      loggedSets: 'id, loggedExerciseId, orden, deletedAt',
      syncState: 'key',
      gyms: 'id, userId, nombre, deletedAt',
    }).upgrade(async (tx) => {
      const days = await tx.table('routineDays').toArray();
      const routineIdByDay = new Map<string, string>(days.map((d) => [d.id, d.routineId]));
      await tx.table('routineExercises').toCollection().modify((re) => {
        re.routineId = routineIdByDay.get(re.routineDayId) ?? '';
        re.updatedAt = Date.now(); // re-sincroniza el cambio
      });
    });
    this.version(7).stores({
      routineDays: null, // eliminar la tabla (datos ya migrados a routineExercises.routineId en v6)
      routineExercises: 'id, routineId, exerciseId, orden, deletedAt',
    });
  }
}

export const db = new GymLogDB();
