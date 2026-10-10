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
    expect(
      PushRequestSchema.safeParse({ mutations: [{ ...base, operations: [] }] }).success,
    ).toBe(false);
    expect(
      PushRequestSchema.safeParse({ mutations: [{ ...base, operations: Array(200).fill(op) }] }).success,
    ).toBe(true);
    expect(
      PushRequestSchema.safeParse({ mutations: [{ ...base, operations: Array(201).fill(op) }] }).success,
    ).toBe(false);
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
