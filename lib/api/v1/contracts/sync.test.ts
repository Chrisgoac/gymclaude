import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  ApiErrorBodySchema,
  PullResponseSchema,
  PushRequestSchema,
  PushResponseSchema,
  SyncTableNameSchema,
} from './sync';

const fixture = (name: string) =>
  JSON.parse(readFileSync(resolve(process.cwd(), 'contracts/v1/fixtures', name), 'utf8'));

describe('sync v1 contracts', () => {
  it('parses push-valid.json', () => {
    expect(PushRequestSchema.safeParse(fixture('push-valid.json')).success).toBe(true);
  });

  it('rejects userId/serverVersion in records', () => {
    const r = PushRequestSchema.safeParse(fixture('push-invalid-server-field.json'));
    expect(r.success).toBe(false);
    for (const field of ['userId', 'serverVersion', 'serverUpdatedAt']) {
      const f = fixture('push-valid.json');
      f.mutations[0].operations[0].record[field] = 'x';
      expect(PushRequestSchema.safeParse(f).success).toBe(false);
    }
  });

  it('rejects server-only table names', () => {
    for (const t of ['users', 'sync_mutations', 'rateLimits', 'deviceSessions']) {
      expect(SyncTableNameSchema.safeParse(t).success).toBe(false);
    }
    expect(SyncTableNameSchema.options).toHaveLength(14);
    const f = fixture('push-valid.json');
    f.mutations[0].operations[0].table = 'users';
    expect(PushRequestSchema.safeParse(f).success).toBe(false);
  });

  it('round-trips pull-page.json', () => {
    const raw = fixture('pull-page.json');
    const parsed = PullResponseSchema.parse(raw);
    expect(JSON.parse(JSON.stringify(parsed))).toEqual(raw);
  });

  it('enforces mutation and operation limits', () => {
    const base = fixture('push-valid.json').mutations[0];
    expect(PushRequestSchema.safeParse({ mutations: [] }).success).toBe(false);
    const many = (n: number) =>
      Array.from({ length: n }, (_, i) => ({
        ...base,
        mutationId: `6f9619ff-8b86-4d11-b42d-${String(i).padStart(12, '0')}`,
      }));
    expect(PushRequestSchema.safeParse({ mutations: many(50) }).success).toBe(true);
    expect(PushRequestSchema.safeParse({ mutations: many(51) }).success).toBe(false);
    const op = base.operations[2];
    const ops = (n: number) =>
      Array.from({ length: n }, (_, i) => ({ ...op, record: { ...op.record, id: `setting-${i}` } }));
    expect(
      PushRequestSchema.safeParse({ mutations: [{ ...base, operations: [] }] }).success,
    ).toBe(false);
    expect(
      PushRequestSchema.safeParse({ mutations: [{ ...base, operations: ops(50) }] }).success,
    ).toBe(true);
    expect(
      PushRequestSchema.safeParse({ mutations: [{ ...base, operations: ops(51) }] }).success,
    ).toBe(false);
  });

  it('rejects duplicate mutationIds within one request', () => {
    const m = fixture('push-valid.json').mutations[0];
    const r = PushRequestSchema.safeParse({ mutations: [m, { ...m, operations: [m.operations[0]] }] });
    expect(r.success).toBe(false);
    expect(r.error?.issues.map((i) => i.path)).toEqual([['mutations', 1, 'mutationId']]);
  });

  it('rejects the same (table, record.id) twice within one mutation', () => {
    const f = fixture('push-valid.json');
    const ops = f.mutations[0].operations;
    ops.push({ ...ops[2], record: { ...ops[2].record, valor: '"other"' } });
    const r = PushRequestSchema.safeParse(f);
    expect(r.success).toBe(false);
    expect(r.error?.issues.map((i) => i.path)).toEqual([['mutations', 0, 'operations', 3, 'record', 'id']]);
    // Same id in different tables is fine.
    const g = fixture('push-valid.json');
    g.mutations[0].operations.push({
      table: 'gyms', baseServerVersion: null,
      record: { id: 'restSeconds', updatedAt: 1, deletedAt: null, nombre: 'G', orden: 0, archivada: false },
    });
    expect(PushRequestSchema.safeParse(g).success).toBe(true);
  });

  it('bounds integer fields to int32', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const withOrden = (orden: any) => {
      const f = fixture('push-valid.json');
      f.mutations[0].operations[1].record.orden = orden;
      return PushRequestSchema.safeParse(f).success;
    };
    expect(withOrden(2147483647)).toBe(true);
    expect(withOrden(-2147483648)).toBe(true);
    expect(withOrden(2147483648)).toBe(false);
    expect(withOrden(-2147483649)).toBe(false);
  });

  it('rejects U+0000 in string fields and inside json values', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const mk = (patch: (f: Record<string, any>) => void) => {
      const f = fixture('push-valid.json');
      patch(f);
      return PushRequestSchema.safeParse(f).success;
    };
    expect(mk((f) => (f.mutations[0].operations[0].record.nombre = 'a\u0000b'))).toBe(false);
    expect(mk((f) => (f.mutations[0].operations[0].record.notas = '\u0000'))).toBe(false);
    expect(mk((f) => (f.mutations[0].operations[2].record.valor = '"x\u0000"'))).toBe(false);
    expect(mk((f) => (f.mutations[0].deviceId = 'dev\u0000'))).toBe(false);
    const meso = (progresion: unknown) =>
      PushRequestSchema.safeParse({
        mutations: [{
          mutationId: '6f9619ff-8b86-4d11-b42d-00c04fc964ff', deviceId: 'd',
          operations: [{
            table: 'mesocycles', baseServerVersion: null,
            record: {
              id: 'm1', updatedAt: 1, deletedAt: null, nombre: 'M', objetivo: 'o', semanas: 4,
              diasPorSemana: 3, notas: null, progresion, fechaInicio: 1,
            },
          }],
        }],
      }).success;
    expect(meso({ weeks: [{ note: 'ok' }] })).toBe(true);
    expect(meso({ weeks: [{ note: 'bad\u0000' }] })).toBe(false);
    expect(meso({ ['k\u0000']: 1 })).toBe(false);
    expect(meso(['\u0000'])).toBe(false);
  });

  it('validates ids, deviceId, timestamps and versions', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const mk = (patch: (f: Record<string, any>) => void) => {
      const f = fixture('push-valid.json');
      patch(f);
      return PushRequestSchema.safeParse(f).success;
    };
    expect(mk((f) => (f.mutations[0].mutationId = 'not-a-uuid'))).toBe(false);
    expect(mk((f) => (f.mutations[0].deviceId = ''))).toBe(false);
    expect(mk((f) => (f.mutations[0].deviceId = 'x'.repeat(129)))).toBe(false);
    expect(mk((f) => (f.mutations[0].operations[0].record.id = 'bad id!'))).toBe(false);
    expect(mk((f) => (f.mutations[0].operations[0].record.id = 'a'.repeat(129)))).toBe(false);
    expect(mk((f) => (f.mutations[0].operations[0].record.id = 'seed-x.1:y_z'))).toBe(true);
    expect(mk((f) => (f.mutations[0].operations[0].record.updatedAt = 2 ** 53))).toBe(false);
    expect(mk((f) => (f.mutations[0].operations[0].record.updatedAt = 1.5))).toBe(false);
    expect(mk((f) => (f.mutations[0].operations[0].baseServerVersion = 42))).toBe(false);
    expect(mk((f) => (f.mutations[0].operations[0].baseServerVersion = '4x'))).toBe(false);
    expect(mk((f) => (f.mutations[0].operations[0].extra = 1))).toBe(false);
    expect(mk((f) => (f.mutations[0].extra = 1))).toBe(false);
    expect(mk((f) => delete f.mutations[0].operations[1].record.repsObjetivoMin)).toBe(true);
  });

  it('parses responses and error body', () => {
    expect(
      PushResponseSchema.safeParse({
        acceptedMutationIds: ['6f9619ff-8b86-4d11-b42d-00c04fc964ff'],
        conflicts: [
          { mutationId: '6f9619ff-8b86-4d11-b42d-00c04fc964ff', table: 'gyms', recordId: 'gym-1', serverVersion: '7', reason: 'stale_base_version' },
        ],
      }).success,
    ).toBe(true);
    expect(
      ApiErrorBodySchema.safeParse({
        error: { code: 'validation_failed', message: 'bad', retryable: false, details: { 'mutations.0': ['required'] } },
      }).success,
    ).toBe(true);
    expect(ApiErrorBodySchema.safeParse({ error: { code: 'x', message: 'y' } }).success).toBe(false);
  });
});
