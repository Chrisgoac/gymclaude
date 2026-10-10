import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  PullResponseSchema,
  PushRequestSchema,
  SYNC_TABLE_NAMES,
  SyncTableNameSchema,
} from './sync';
import { decodeCursor, encodeCursor } from '@/lib/api/v1/sync/cursor';

const dir = resolve(process.cwd(), 'contracts/v1/fixtures');
const fixture = (name: string) => JSON.parse(readFileSync(resolve(dir, name), 'utf8'));

describe('contract fixtures', () => {
  it('push-valid.json is a valid PushRequest', () => {
    expect(PushRequestSchema.safeParse(fixture('push-valid.json')).success).toBe(true);
  });

  it('push-invalid-server-field.json is rejected', () => {
    expect(PushRequestSchema.safeParse(fixture('push-invalid-server-field.json')).success).toBe(false);
  });

  it('pull-page.json is a valid PullResponse', () => {
    expect(PullResponseSchema.safeParse(fixture('pull-page.json')).success).toBe(true);
  });

  it('every cursor in fixtures decodes with decodeCursor', () => {
    for (const name of ['pull-page.json', 'bootstrap-all-entities.json']) {
      const { cursor } = fixture(name);
      expect(() => decodeCursor(cursor), name).not.toThrow();
      expect(encodeCursor(decodeCursor(cursor))).toBe(cursor);
    }
    const lastVersion = (name: string) => {
      const { changes, cursor } = fixture(name);
      return { last: changes[changes.length - 1].serverVersion as string, cursor };
    };
    for (const name of ['pull-page.json', 'bootstrap-all-entities.json']) {
      const { last, cursor } = lastVersion(name);
      expect(decodeCursor(cursor)).toBe(BigInt(last));
    }
    const cases = fixture('pull-pagination-cases.json');
    for (const c of cases.cursorRoundTrips) expect(encodeCursor(decodeCursor(encodeCursor(BigInt(c))))).toBe(encodeCursor(BigInt(c)));
    for (const c of cases.invalidCursors) expect(() => decodeCursor(c.cursor), c.name).toThrow();
  });

  describe('bootstrap-all-entities.json', () => {
    const raw = fixture('bootstrap-all-entities.json');

    it('is a valid PullResponse that round-trips', () => {
      const parsed = PullResponseSchema.parse(raw);
      expect(JSON.parse(JSON.stringify(parsed))).toEqual(raw);
      expect(raw.hasMore).toBe(false);
    });

    it('has one live row and one tombstone for each of the 14 tables', () => {
      expect(raw.changes).toHaveLength(SYNC_TABLE_NAMES.length * 2);
      for (const table of SYNC_TABLE_NAMES) {
        const rows = raw.changes.filter((c: { table: string }) => c.table === table);
        expect(rows, table).toHaveLength(2);
        expect(rows.filter((c: { record: { deletedAt: number | null } }) => c.record.deletedAt === null)).toHaveLength(1);
        expect(rows.filter((c: { record: { deletedAt: number | null } }) => c.record.deletedAt !== null)).toHaveLength(1);
      }
    });

    it('has strictly increasing serverVersion', () => {
      const versions = raw.changes.map((c: { serverVersion: string }) => BigInt(c.serverVersion));
      for (let i = 1; i < versions.length; i++) expect(versions[i] > versions[i - 1]).toBe(true);
    });

    it('rejects server-only entity names', () => {
      const names = new Set(raw.changes.map((c: { table: string }) => c.table));
      for (const n of names) expect(SyncTableNameSchema.safeParse(n).success).toBe(true);
      for (const t of ['users', 'syncChanges', 'sync_changes', 'syncMutations', 'apiRateBuckets']) {
        expect(names.has(t)).toBe(false);
        expect(SyncTableNameSchema.safeParse(t).success).toBe(false);
      }
    });
  });

  it('every fixture file is covered by this suite', () => {
    expect(readdirSync(dir).sort()).toEqual([
      'bootstrap-all-entities.json',
      'conflict-cases.json',
      'pull-pagination-cases.json',
      'pull-page.json',
      'push-invalid-server-field.json',
      'push-valid.json',
    ].sort());
  });
});
