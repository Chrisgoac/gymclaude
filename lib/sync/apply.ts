import type { Table } from 'dexie';
import { db } from '@/lib/db/database';
import type { SyncMeta } from '@/lib/db/types';
import type { TableChanges } from './types';

const asSync = (t: unknown) => t as Table<SyncMeta, string>;

const TABLE_BY_NAME: Record<string, Table<SyncMeta, string>> = {
  exercises: asSync(db.exercises),
  routines: asSync(db.routines),
  routineExercises: asSync(db.routineExercises),
  workoutSessions: asSync(db.workoutSessions),
  loggedExercises: asSync(db.loggedExercises),
  loggedSets: asSync(db.loggedSets),
  gyms: asSync(db.gyms),
  exercisePhotos: asSync(db.exercisePhotos),
  userSettings: asSync(db.userSettings),
  coachMessages: asSync(db.coachMessages),
  bodyMetrics: asSync(db.bodyMetrics),
  progressPhotos: asSync(db.progressPhotos),
  mesocycles: asSync(db.mesocycles),
};

export function pickWinner<T extends SyncMeta>(local: T | undefined, incoming: T): T {
  if (!local) return incoming;
  return incoming.updatedAt >= local.updatedAt ? incoming : local;
}

export async function applyIncoming(changes: TableChanges[]): Promise<void> {
  for (const { table: name, records } of changes) {
    const table = TABLE_BY_NAME[name];
    if (!table) continue;
    for (const incoming of records) {
      const local = await table.get(incoming.id);
      if (pickWinner(local, incoming) === incoming) {
        await table.put(incoming);
      }
    }
  }
}
