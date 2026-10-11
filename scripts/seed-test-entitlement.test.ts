// @vitest-environment node
import { afterAll, describe, expect, it } from 'vitest';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';

const script = resolve(process.cwd(), 'scripts/seed-test-entitlement.mjs');
const run = (env: Record<string, string | undefined>, args: string[]) =>
  spawnSync(process.execPath, [script, ...args], {
    env: { PATH: process.env.PATH, ...env } as unknown as NodeJS.ProcessEnv,
    encoding: 'utf8',
    timeout: 15_000,
  });

describe('seed-test-entitlement (refusals; never connects)', () => {
  it('refuses without MIGRATION_DATABASE_URL (does not fall back to .env.local)', () => {
    const r = run({}, ['--user', 'user_x']);
    expect(r.status).toBe(1);
    expect(r.stderr).toContain('MIGRATION_DATABASE_URL');
  });

  it('refuses any non-localhost host', () => {
    for (const host of ['ep-cool-db.eu-central-1.aws.neon.tech', '10.0.0.5', 'localhost.evil.com', 'db.example.com']) {
      const r = run({ MIGRATION_DATABASE_URL: `postgresql://u:p@${host}:5432/db` }, ['--user', 'user_x']);
      expect(r.status, host).toBe(1);
      expect(r.stderr).toContain('no es localhost');
    }
  });

  it('requires --user, a known --state and an integer --days in 1..365', () => {
    const local = { MIGRATION_DATABASE_URL: 'postgresql://u:p@127.0.0.1:1/db' };
    expect(run(local, []).stderr).toContain('--user');
    expect(run(local, ['--user', 'u', '--state', 'LIFETIME']).stderr).toContain('--state');
    for (const days of ['0', '366', '1.5', 'abc', '-3']) {
      const r = run(local, ['--user', 'u', '--days', days]);
      expect(r.status, days).toBe(1);
      expect(r.stderr).toContain('--days');
    }
  });
});

const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('seed-test-entitlement (integration, disposable DB)', () => {
  const userId = `test-seed-${crypto.randomUUID()}`;

  afterAll(async () => {
    const { Client } = await import('pg');
    const c = new Client({ connectionString: url });
    await c.connect();
    await c.query('DELETE FROM subscription_entitlements WHERE user_id = $1', [userId]);
    await c.end();
  });

  it('grants a renewable test entitlement that the reader sees as Pro; re-running keeps one row', async () => {
    const { drizzle } = await import('drizzle-orm/node-postgres');
    const { Pool } = await import('pg');
    const { createDbEntitlementReader } = await import('@/lib/entitlements/reader');
    for (let i = 0; i < 2; i++) {
      const r = run({ MIGRATION_DATABASE_URL: url }, ['--user', userId, '--days', '7']);
      expect(r.status, r.stderr).toBe(0);
    }
    const pool = new Pool({ connectionString: url, max: 1 });
    try {
      const db = drizzle(pool);
      expect(await createDbEntitlementReader(() => db).isPro(userId, Date.now())).toBe(true);
      const rows = await pool.query('SELECT source, state FROM subscription_entitlements WHERE user_id = $1', [userId]);
      expect(rows.rows).toEqual([{ source: 'test', state: 'ACTIVE' }]);
      expect(run({ MIGRATION_DATABASE_URL: url }, ['--user', userId, '--state', 'REVOKED']).status).toBe(0);
      expect(await createDbEntitlementReader(() => db).isPro(userId, Date.now())).toBe(false);
    } finally {
      await pool.end();
    }
  });
});
