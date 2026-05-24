import type { Table } from 'dexie';
import { db } from '@/lib/db/database';
import type { SyncMeta } from '@/lib/db/types';
import type { TableChanges } from './types';

interface SyncableTable {
  name: string;
  table: Table<SyncMeta, string>;
  shouldSync?: (r: SyncMeta) => boolean;
}

const asSync = (t: unknown) => t as Table<SyncMeta, string>;

export const SYNCABLE_TABLES: SyncableTable[] = [
  { name: 'exercises', table: asSync(db.exercises), shouldSync: (r) => (r as SyncMeta & { esPersonalizado?: boolean }).esPersonalizado === true },
  { name: 'routines', table: asSync(db.routines) },
  { name: 'routineDays', table: asSync(db.routineDays) },
  { name: 'routineExercises', table: asSync(db.routineExercises) },
  { name: 'workoutSessions', table: asSync(db.workoutSessions) },
  { name: 'loggedExercises', table: asSync(db.loggedExercises) },
  { name: 'loggedSets', table: asSync(db.loggedSets) },
  { name: 'gyms', table: asSync(db.gyms) },
];

export async function collectDirty(sinceUpdatedAt: number): Promise<TableChanges[]> {
  const out: TableChanges[] = [];
  for (const { name, table, shouldSync } of SYNCABLE_TABLES) {
    const all = await table.toArray();
    let records = all.filter((r) => r.updatedAt > sinceUpdatedAt);
    if (shouldSync) records = records.filter(shouldSync);
    if (records.length) out.push({ table: name, records });
  }
  return out;
}
