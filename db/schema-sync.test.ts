// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { getTableConfig, type PgTable } from 'drizzle-orm/pg-core';
import * as schema from './schema';
import { SYNC_TABLE_NAMES } from '@/lib/api/v1/contracts/sync';

const SYNCED: Record<(typeof SYNC_TABLE_NAMES)[number], PgTable> = {
  exercises: schema.exercises,
  routines: schema.routines,
  routineExercises: schema.routineExercises,
  workoutSessions: schema.workoutSessions,
  loggedExercises: schema.loggedExercises,
  loggedSets: schema.loggedSets,
  gyms: schema.gyms,
  exercisePhotos: schema.exercisePhotos,
  userSettings: schema.userSettings,
  coachMessages: schema.coachMessages,
  bodyMetrics: schema.bodyMetrics,
  progressPhotos: schema.progressPhotos,
  mesocycles: schema.mesocycles,
  achievements: schema.achievements,
};

function column(table: PgTable, name: string) {
  const col = getTableConfig(table).columns.find((c) => c.name === name);
  if (!col) throw new Error(`${getTableConfig(table).name}.${name} missing`);
  return col;
}

describe('synced table metadata', () => {
  it('covers exactly the 14 contract tables', () => {
    expect(Object.keys(SYNCED).sort()).toEqual([...SYNC_TABLE_NAMES].sort());
  });

  it.each(Object.entries(SYNCED))('%s has sync columns', (_name, table) => {
    expect(column(table, 'user_id').notNull).toBe(true);
    expect(column(table, 'updated_at').getSQLType()).toBe('bigint');
    expect(column(table, 'deleted_at').notNull).toBe(false);
    const sv = column(table, 'server_version');
    expect(sv.getSQLType()).toBe('bigint');
    // Nullable so legacy writers keep working; the migration backfills every row.
    expect(sv.notNull).toBe(false);
    expect(column(table, 'last_device_id').getSQLType()).toBe('text');
    expect(column(table, 'last_mutation_id').getSQLType()).toBe('text');
  });
});

describe('drift fields', () => {
  it.each([
    ['exercises', 'descripcion', 'text'],
    ['exercises', 'execution', 'text'],
    ['exercises', 'incremento_kg', 'double precision'],
    ['routineExercises', 'reps_objetivo_min', 'integer'],
  ] as const)('%s.%s is a nullable %s', (table, name, type) => {
    const col = column(SYNCED[table], name);
    expect(col.getSQLType()).toBe(type);
    expect(col.notNull).toBe(false);
  });
});

describe('sync infrastructure tables', () => {
  it('sync_mutations has a (user_id, mutation_id) primary key', () => {
    const cfg = getTableConfig(schema.syncMutations);
    expect(cfg.name).toBe('sync_mutations');
    expect(cfg.primaryKeys[0].columns.map((c) => c.name)).toEqual(['user_id', 'mutation_id']);
    expect(column(schema.syncMutations, 'response_json').getSQLType()).toBe('jsonb');
    expect(column(schema.syncMutations, 'processed_at').notNull).toBe(true);
  });

  it('sync_changes is a bigserial journal indexed by (user_id, sequence)', () => {
    const cfg = getTableConfig(schema.syncChanges);
    expect(cfg.name).toBe('sync_changes');
    const seq = column(schema.syncChanges, 'sequence');
    expect(seq.getSQLType()).toBe('bigserial');
    expect(seq.primary).toBe(true);
    for (const n of ['user_id', 'table_name', 'record_id', 'changed_at']) {
      expect(column(schema.syncChanges, n).notNull).toBe(true);
    }
    const idx = cfg.indexes.map((i) => i.config.columns.map((c) => ('name' in c ? c.name : '')));
    expect(idx).toContainEqual(['user_id', 'sequence']);
  });

  it('api_rate_buckets is keyed by (scope, subject_hash, window_start)', () => {
    const cfg = getTableConfig(schema.apiRateBuckets);
    expect(cfg.name).toBe('api_rate_buckets');
    expect(cfg.primaryKeys[0].columns.map((c) => c.name)).toEqual(['scope', 'subject_hash', 'window_start']);
    expect(column(schema.apiRateBuckets, 'count').getSQLType()).toBe('integer');
  });
});
