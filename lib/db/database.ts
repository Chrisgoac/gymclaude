import Dexie, { type Table } from 'dexie';
import type {
  Exercise, Routine, RoutineDay, RoutineExercise,
  WorkoutSession, LoggedExercise, LoggedSet, SyncState, Gym,
} from './types';

export class GymLogDB extends Dexie {
  exercises!: Table<Exercise, string>;
  routines!: Table<Routine, string>;
  routineDays!: Table<RoutineDay, string>;
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
  }
}

export const db = new GymLogDB();
