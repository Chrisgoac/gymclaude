import Dexie, { type Table } from 'dexie';
import type { Exercise } from './types';

export class GymLogDB extends Dexie {
  exercises!: Table<Exercise, string>;

  constructor() {
    super('gymlog');
    this.version(1).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
    });
  }
}

export const db = new GymLogDB();
