// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { PoolTx } from '@/db/pool';
import { PushRequestSchema, SyncTableNameSchema, SYNC_TABLE_NAMES } from '@/lib/api/v1/contracts/sync';
import { ApiError } from '@/lib/api/v1/errors';
import { pushMutations } from '@/lib/api/v1/sync/push';
import { SYNC_TABLE_ADAPTERS } from '@/lib/api/v1/sync/tables';
import * as schema from '@/db/schema';

// Pro entitlements and AI artifacts are server-owned: no client may write them through generic sync.
const SERVER_ONLY = [
  'subscriptionEntitlements', 'subscription_entitlements',
  'aiReports', 'ai_reports',
  'reportedAiResponses', 'reported_ai_responses',
  'aiResponses', 'ai_responses',
];

const pushNaming = (table: string) => {
  const f = JSON.parse(readFileSync(resolve(process.cwd(), 'contracts/v1/fixtures/push-valid.json'), 'utf8'));
  f.mutations[0].operations[0].table = table;
  return f;
};

describe('server-only AI/entitlement tables never enter generic sync', () => {
  it('exist in the database schema', () => {
    expect(schema.subscriptionEntitlements).toBeDefined();
    expect(schema.aiReports).toBeDefined();
    expect(schema.reportedAiResponses).toBeDefined();
    expect(schema.aiResponses).toBeDefined();
  });

  it('are not sync table names and have no sync adapter', () => {
    for (const t of SERVER_ONLY) {
      expect(SyncTableNameSchema.safeParse(t).success, t).toBe(false);
      expect((SYNC_TABLE_NAMES as readonly string[]).includes(t)).toBe(false);
      expect(Object.keys(SYNC_TABLE_ADAPTERS)).not.toContain(t);
    }
  });

  it('a push naming any of them fails schema validation', () => {
    for (const t of SERVER_ONLY) {
      expect(PushRequestSchema.safeParse(pushNaming(t)).success, t).toBe(false);
    }
  });

  it('pushMutations rejects them defensively with UNKNOWN_TABLE even if the schema were bypassed', async () => {
    // Only the dedupe claim is allowed; any record read/write would hit the throwing members.
    const claim = { values: () => ({ onConflictDoNothing: () => ({ returning: async () => [{ mutationId: 'm' }] }) }) };
    const tx: Record<string, unknown> = {
      insert: () => claim,
      select: () => { throw new Error('db read'); },
      update: () => { throw new Error('db write'); },
      execute: () => { throw new Error('db execute'); },
    };
    tx.transaction = async (fn: (t: unknown) => Promise<unknown>) => fn(tx);
    for (const t of SERVER_ONLY) {
      let caught: unknown;
      try {
        await pushMutations(tx as unknown as PoolTx, { userId: 'u' }, pushNaming(t), { now: 1_767_225_600_000 });
      } catch (e) {
        caught = e;
      }
      expect(caught, t).toBeInstanceOf(ApiError);
      expect(caught).toMatchObject({ status: 400, code: 'UNKNOWN_TABLE' });
    }
  });
});
