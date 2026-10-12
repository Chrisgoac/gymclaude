// @vitest-environment node
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { getTableConfig, type PgTable } from 'drizzle-orm/pg-core';
import { eq, sql } from 'drizzle-orm';
import * as schema from '@/db/schema';
import type { PoolDb } from '@/db/pool';
import { hashSubject } from '@/lib/api/v1/rate-limit';
import { enqueueMediaDeletion, mediaObjectKey, userHash } from '@/lib/api/v1/media/deletions';
import { storeAiReport } from '@/lib/api/v1/ai/store';
import { upsertPlayInTx } from '@/lib/billing/entitlements';
import { lockUserSync } from '@/lib/api/v1/sync/lock';
import type { EntitlementRow } from '@/lib/billing/entitlements';
import { accountHash, isAccountDeleting } from './access';
import {
  activeSubscriptionFor,
  createAccountDeletionService,
  deleteAccount,
  DeleteAccountRequestSchema,
  FINAL_SWEEP_DELAY_MS,
  INDIRECT_USER_TABLES,
  isRecentAuth,
  playManageUrl,
  SERVER_USER_TABLES,
  SYNCED_USER_TABLES,
  type AccountDeletionService,
  type AccountStorage,
  type IdentityProvider,
} from './deletion';

const NOW = 1_767_225_600_000;
const DAY = 86_400_000;
const FAR_FUTURE = Date.UTC(2100, 0, 1);

describe('recent authentication and confirmation', () => {
  it('accepts a first factor verified within the last 10 minutes only', () => {
    expect(isRecentAuth([0, -1])).toBe(true);
    expect(isRecentAuth([9, -1])).toBe(true);
    expect(isRecentAuth([10, -1])).toBe(false); // Clerk's rule: afterMinutes > age
    expect(isRecentAuth([11, 0])).toBe(false);
    expect(isRecentAuth([-1, 0])).toBe(false); // never verified
    expect(isRecentAuth(null)).toBe(false); // token without the fva claim: fail closed
    expect(isRecentAuth(undefined)).toBe(false);
  });

  it('requires exactly { confirm: "ELIMINAR" }', () => {
    expect(DeleteAccountRequestSchema.safeParse({ confirm: 'ELIMINAR' }).success).toBe(true);
    expect(DeleteAccountRequestSchema.safeParse({ confirm: 'eliminar' }).success).toBe(false);
    expect(DeleteAccountRequestSchema.safeParse({}).success).toBe(false);
    expect(DeleteAccountRequestSchema.safeParse({ confirm: 'ELIMINAR', userId: 'other' }).success).toBe(false);
  });
});

describe('final sweep delay', () => {
  it('waits longer than the slowest route that may still be writing for the user, with margin', async () => {
    const { readdirSync, readFileSync } = await import('node:fs');
    const { join } = await import('node:path');
    const routes = (readdirSync(join(process.cwd(), 'app'), { recursive: true }) as string[])
      .filter((f) => /(^|\/)route\.tsx?$/.test(f));
    const durations = routes.flatMap((f) => {
      const m = /export const maxDuration = (\d+)/.exec(readFileSync(join(process.cwd(), 'app', f), 'utf8'));
      return m ? [Number(m[1])] : [];
    });
    expect(Math.max(...durations)).toBe(120);
    expect(FINAL_SWEEP_DELAY_MS).toBe(180_000);
    expect(FINAL_SWEEP_DELAY_MS).toBeGreaterThanOrEqual(Math.max(...durations) * 1000 + 60_000);
  });
});

describe('data scope', () => {
  it('covers every table with a user_id column; the others are deleted by an exact indirect key', () => {
    const tables = Object.values(schema as Record<string, unknown>).filter(
      (v): v is PgTable => typeof v === 'object' && v !== null && Symbol.for('drizzle:IsDrizzleTable') in v,
    );
    const covered = new Set([...SYNCED_USER_TABLES, ...SERVER_USER_TABLES].map((t) => getTableConfig(t).name));
    for (const table of tables) {
      const config = getTableConfig(table);
      const hasUserId = config.columns.some((c) => c.name === 'user_id');
      if (hasUserId && config.name !== 'account_deletion_jobs') {
        expect(covered, `${config.name} is user-scoped and must be deleted`).toContain(config.name);
      } else {
        expect(INDIRECT_USER_TABLES as readonly string[], `${config.name} needs an explicit scope`).toContain(config.name);
      }
    }
    expect(SYNCED_USER_TABLES).toHaveLength(14);
  });
});

describe('active subscription warning', () => {
  const row = (over: Partial<EntitlementRow> = {}): EntitlementRow => ({
    userId: 'u', productId: 'gymlog_pro_monthly', purchaseTokenHash: 'h', state: 'ACTIVE', expiresAt: NOW + DAY,
    autoRenewing: true, lastVerifiedAt: NOW, source: 'play', ...over,
  });
  const store = (r: EntitlementRow | null) => ({ findBest: vi.fn(async () => r) });

  it('reports a renewing Play subscription with a Play management link', async () => {
    const sub = await activeSubscriptionFor(store(row()), 'u', NOW);
    expect(sub).toEqual({
      productId: 'gymlog_pro_monthly', state: 'ACTIVE', validUntil: NOW + DAY, willRenew: true,
      manageUrl: playManageUrl('gymlog_pro_monthly'),
    });
    expect(sub?.manageUrl).toMatch(/^https:\/\/play\.google\.com\/store\/account\/subscriptions\?package=com\.chrisgoac\.gymlog&sku=gymlog_pro_monthly$/);
  });

  it('reports a cancelled one still inside its paid period as not renewing', async () => {
    expect(await activeSubscriptionFor(store(row({ autoRenewing: false })), 'u', NOW)).toMatchObject({ state: 'CANCELED', willRenew: false });
  });

  it('ignores none, expired and test entitlements', async () => {
    expect(await activeSubscriptionFor(store(null), 'u', NOW)).toBeNull();
    expect(await activeSubscriptionFor(store(row({ state: 'EXPIRED', expiresAt: NOW - 1 })), 'u', NOW)).toBeNull();
    expect(await activeSubscriptionFor(store(row({ autoRenewing: false, expiresAt: NOW - 1 })), 'u', NOW)).toBeNull();
    expect(await activeSubscriptionFor(store(row({ source: 'test' })), 'u', NOW)).toBeNull();
  });
});

describe('deleteAccount use case', () => {
  function fakeService(job: Awaited<ReturnType<AccountDeletionService['findJob']>>) {
    const calls: string[] = [];
    const service = {
      findJob: vi.fn(async () => (calls.push('find'), job)),
      readActiveSubscription: vi.fn(async () => (calls.push('subscription'), null)),
      requestDeletion: vi.fn(async () => (calls.push('request'), true)),
      runJob: vi.fn(async () => (calls.push('run'), { status: 'deleted' as const, step: 'done' as const, errorCode: null })),
      resumePending: vi.fn(),
    } satisfies AccountDeletionService;
    return { service, calls };
  }

  it('refuses a new request without recent authentication and changes nothing', async () => {
    const { service, calls } = fakeService(null);
    await expect(deleteAccount(service, { userId: 'u', factorVerificationAge: [30, -1] })).rejects.toMatchObject({
      status: 403, code: 'REAUTH_REQUIRED',
    });
    expect(calls).toEqual(['find']);
  });

  it('reads the subscription before locking, then runs the job', async () => {
    const { service, calls } = fakeService(null);
    const result = await deleteAccount(service, { userId: 'u', factorVerificationAge: [2, -1] });
    expect(result).toEqual({ status: 'deleted', step: 'done', errorCode: null, activeSubscription: null, finalCleanupPending: false });
    expect(calls).toEqual(['find', 'subscription', 'request', 'run']);
  });

  it('resumes an already requested deletion without asking for recent authentication again', async () => {
    const { service, calls } = fakeService({ userIdHash: 'h', status: 'pending', step: 'identity', attempts: 1, lastErrorCode: 'IDENTITY_FAILED' });
    await deleteAccount(service, { userId: 'u', factorVerificationAge: null });
    expect(calls).toEqual(['find', 'subscription', 'run']);
  });

  it('answers a completed deletion as deleted without running anything or spending the rate limit', async () => {
    const { service, calls } = fakeService({ userIdHash: 'h', status: 'done', step: 'done', attempts: 1, lastErrorCode: null });
    const beforeWork = vi.fn(async () => {});
    expect(await deleteAccount(service, { userId: 'u', factorVerificationAge: null, beforeWork })).toMatchObject({ status: 'deleted' });
    expect(calls).toEqual(['find']);
    expect(beforeWork).not.toHaveBeenCalled();
  });
});

// ── Against the disposable Postgres (SYNC_TEST_DATABASE_URL), with fake R2 and Clerk ──

/** In-memory R2: two buckets, prefix listing, one-shot failure injection. */
function fakeStorage() {
  const buckets = { private: new Set<string>(), public: new Set<string>() };
  const failures: Partial<Record<keyof AccountStorage, number>> = {};
  const calls: { op: keyof AccountStorage; arg: string }[] = [];
  const guard = (op: keyof AccountStorage, arg: string) => {
    calls.push({ op, arg });
    if ((failures[op] ?? 0) > 0) {
      failures[op]!--;
      throw new Error(`fake R2 ${op} failure`);
    }
  };
  const storage: AccountStorage = {
    async listPrivate(prefix) {
      guard('listPrivate', prefix);
      return [...buckets.private].filter((k) => k.startsWith(prefix));
    },
    async listLegacy(prefix) {
      guard('listLegacy', prefix);
      return [...buckets.public].filter((k) => k.startsWith(prefix));
    },
    async removePrivate(key) {
      guard('removePrivate', key);
      buckets.private.delete(key);
    },
    async removeLegacy(key) {
      guard('removeLegacy', key);
      buckets.public.delete(key);
    },
  };
  return { storage, buckets, failures, calls };
}

/** Fake Clerk: deleting an unknown user succeeds (idempotent, like the 404 mapping in production). */
function fakeIdentity(users: string[]) {
  const live = new Set(users);
  let failures = 0;
  const identity: IdentityProvider & { live: Set<string>; failNext(n: number): void; calls: string[] } = {
    live,
    calls: [],
    failNext(n) {
      failures = n;
    },
    async deleteUser(userId) {
      identity.calls.push(userId);
      if (failures > 0) {
        failures--;
        throw new Error('fake Clerk failure');
      }
      live.delete(userId);
    },
  };
  return identity;
}

const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('account deletion orchestrator (integration)', () => {
  let pool: import('pg').Pool;
  let db: PoolDb;
  const created: string[] = [];

  beforeAll(async () => {
    const { drizzle } = await import('drizzle-orm/node-postgres');
    const { Pool } = await import('pg');
    pool = new Pool({ connectionString: url, max: 6 });
    db = drizzle(pool, { schema });
  });
  afterAll(async () => {
    if (!db) return;
    for (const u of created) await wipe(u);
    await pool.end();
  });

  async function wipe(userId: string) {
    await db.execute(sql`DELETE FROM billing_events WHERE message_id LIKE ${`msg-${userId}%`}`);
    await db.execute(sql`DELETE FROM api_rate_buckets WHERE subject_hash = ${hashSubject(userId)}`);
    for (const t of [...SERVER_USER_TABLES, ...SYNCED_USER_TABLES] as (PgTable & { userId: typeof schema.gyms.userId })[]) {
      await db.delete(t).where(eq(t.userId, userId));
    }
    await db.execute(sql`DELETE FROM account_deletion_jobs WHERE user_id_hash = ${accountHash(userId)}`);
  }

  /** Two users whose ids share a prefix (B = A + "x"): legacy `A/` must never match `Ax/…`. */
  function newPair() {
    const a = `test-acct-${crypto.randomUUID()}`;
    const b = `${a}x`;
    created.push(a, b);
    return { a, b };
  }

  const tokenHash = (userId: string) => `tokhash-${userId}`;

  async function seed(userId: string, storage: ReturnType<typeof fakeStorage>, opts: { foreignKey?: string } = {}) {
    const s = { userId, updatedAt: NOW, serverUpdatedAt: NOW };
    const id = (t: string) => `${t}-${userId}`;
    const privateKey = mediaObjectKey(userId, 'progress', crypto.randomUUID());
    const privateExerciseKey = mediaObjectKey(userId, 'exercise', crypto.randomUUID());
    const legacyKey = `${userId}/e1/${crypto.randomUUID()}.jpg`;
    const pendingKey = mediaObjectKey(userId, 'exercise', crypto.randomUUID());
    storage.buckets.private.add(privateKey).add(privateExerciseKey).add(pendingKey);
    storage.buckets.public.add(legacyKey);
    // An orphan object with no row left (e.g. a crashed upload): found by the prefix listing.
    storage.buckets.private.add(mediaObjectKey(userId, 'progress', 'orphan'));
    storage.buckets.public.add(`${userId}/orphan.jpg`);

    await db.insert(schema.exercises).values({ ...s, id: id('ex'), nombre: 'Press', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'fuerza', esPersonalizado: true });
    await db.insert(schema.routines).values({ ...s, id: id('ro'), nombre: 'R', archivada: false });
    await db.insert(schema.routineExercises).values({ ...s, id: id('re'), routineId: id('ro'), exerciseId: id('ex'), orden: 0 });
    await db.insert(schema.workoutSessions).values({ ...s, id: id('ws'), fecha: NOW });
    await db.insert(schema.loggedExercises).values({ ...s, id: id('le'), sessionId: id('ws'), exerciseId: id('ex'), orden: 0 });
    await db.insert(schema.loggedSets).values({ ...s, id: id('ls'), loggedExerciseId: id('le'), orden: 0, peso: 60, reps: 8 });
    await db.insert(schema.gyms).values({ ...s, id: id('gy'), nombre: 'G', orden: 0, archivada: false });
    await db.insert(schema.exercisePhotos).values([
      { ...s, id: id('ep'), exerciseId: id('ex'), url: '', key: privateExerciseKey },
      { ...s, id: id('ep-legacy'), exerciseId: id('ex'), url: '', key: legacyKey },
      // A key of ANOTHER user smuggled through sync: never deleted on this user's behalf.
      ...(opts.foreignKey ? [{ ...s, id: id('ep-foreign'), exerciseId: id('ex'), url: '', key: opts.foreignKey }] : []),
    ]);
    await db.insert(schema.userSettings).values({ ...s, id: 'units', valor: '"kg"' });
    await db.insert(schema.coachMessages).values({ ...s, id: id('cm'), rol: 'user', contenido: 'hola', createdAt: NOW });
    await db.insert(schema.bodyMetrics).values({ ...s, id: id('bm'), tipo: 'peso', valor: 80, fecha: NOW });
    await db.insert(schema.progressPhotos).values({ ...s, id: id('pp'), url: '', key: privateKey, fecha: NOW, angulo: 'frente' });
    await db.insert(schema.mesocycles).values({ ...s, id: id('me'), nombre: 'M', objetivo: 'fuerza', semanas: 4, diasPorSemana: 3, progresion: {}, fechaInicio: NOW });
    await db.insert(schema.achievements).values({ ...s, id: id('ac'), clave: 'primer', fechaDesbloqueo: NOW });

    await db.insert(schema.syncMutations).values({ userId, mutationId: id('mut'), responseJson: {}, processedAt: NOW });
    await db.insert(schema.syncChanges).values({ userId, tableName: 'gyms', recordId: id('gy'), changedAt: NOW });
    await db.insert(schema.apiRateBuckets).values({ scope: 'sync', subjectHash: hashSubject(userId), windowStart: NOW, count: 1 });
    // Due far in the future: the media suites' processDeletions (global, own clock) must never claim it.
    await db.insert(schema.mediaDeletions).values({ objectKey: pendingKey, userId, dueAt: FAR_FUTURE, createdAt: NOW });
    await db.insert(schema.subscriptionEntitlements).values({
      userId, productId: 'gymlog_pro_annual', purchaseTokenHash: tokenHash(userId), state: 'ACTIVE', expiresAt: Date.now() + 30 * DAY,
      autoRenewing: true, lastVerifiedAt: NOW, source: 'play', createdAt: NOW, updatedAt: NOW,
    });
    await db.insert(schema.billingEvents).values({
      messageId: `msg-${userId}-1`, eventTime: NOW, notificationKind: 'subscription', purchaseTokenHash: tokenHash(userId), receivedAt: NOW,
    });
    await db.insert(schema.aiReports).values({ id: id('ar'), userId, kind: 'weekly', sourceFingerprint: 'f', payload: {}, model: 'm', createdAt: NOW });
    await db.insert(schema.aiResponses).values({ id: id('ai'), userId, clientMessageId: id('cmid'), status: 'complete', createdAt: NOW, updatedAt: NOW });
    await db.insert(schema.reportedAiResponses).values({ id: id('rp'), userId, responseId: id('ai'), category: 'other', createdAt: NOW });
    return { privateKey, legacyKey };
  }

  /** Row count per scoped table for a user (0 everywhere = fully deleted). */
  async function counts(userId: string): Promise<Record<string, number>> {
    const out: Record<string, number> = {};
    for (const t of [...SERVER_USER_TABLES, ...SYNCED_USER_TABLES] as (PgTable & { userId: typeof schema.gyms.userId })[]) {
      const [{ n }] = await db.select({ n: sql<number>`count(*)::int` }).from(t).where(eq(t.userId, userId));
      out[getTableConfig(t).name] = n;
    }
    const rate = await db.execute(sql`SELECT count(*)::int AS n FROM api_rate_buckets WHERE subject_hash = ${hashSubject(userId)}`);
    out.api_rate_buckets = Number(rate.rows[0].n);
    const billing = await db.execute(sql`SELECT count(*)::int AS n FROM billing_events WHERE purchase_token_hash = ${tokenHash(userId)}`);
    out.billing_events = Number(billing.rows[0].n);
    return out;
  }
  const total = (c: Record<string, number>) => Object.values(c).reduce((a, b) => a + b, 0);
  const privatePrefix = (userId: string) => `users/${userHash(userId)}/`;
  const objectsOf = (storage: ReturnType<typeof fakeStorage>, userId: string) => [
    ...[...storage.buckets.private].filter((k) => k.startsWith(privatePrefix(userId))),
    ...[...storage.buckets.public].filter((k) => k.startsWith(`${userId}/`)),
  ];

  /** Test clock: the final sweep only runs FINAL_SWEEP_DELAY_MS after the request. */
  let clock = Date.now();
  beforeEach(() => {
    clock = Date.now();
  });
  const passSweepDelay = () => {
    clock += FINAL_SWEEP_DELAY_MS;
  };

  function service(storage: AccountStorage, identity: IdentityProvider, extra: Partial<Parameters<typeof createAccountDeletionService>[0]> = {}) {
    return createAccountDeletionService({ db, storage, identity, log: () => {}, now: () => clock, ...extra });
  }

  it('deletes every row and object of the user, the Clerk user, and nothing of another user', async () => {
    const { a, b } = newPair();
    const r2 = fakeStorage();
    const bKeys = await seed(b, r2);
    await seed(a, r2, { foreignKey: bKeys.privateKey });
    const clerk = fakeIdentity([a, b]);
    const before = await counts(b);
    expect(Object.values(await counts(a)).every((n) => n >= 1)).toBe(true);

    const svc = service(r2.storage, clerk);
    const result = await deleteAccount(svc, { userId: a, factorVerificationAge: [1, -1] });

    // Deleted for the user; only the delayed final sweep is left (cron).
    expect(result).toMatchObject({ status: 'deleted', step: 'database_sweep', errorCode: null, finalCleanupPending: true });
    expect(result.activeSubscription).toMatchObject({ productId: 'gymlog_pro_annual', state: 'ACTIVE', willRenew: true });
    expect(total(await counts(a))).toBe(0);
    expect(objectsOf(r2, a)).toEqual([]);
    expect(clerk.live.has(a)).toBe(false);
    // Cross-user safety: B (whose id has A's id as prefix, and whose key A's row cited) is untouched.
    expect(await counts(b)).toEqual(before);
    expect(r2.buckets.private.has(bKeys.privateKey)).toBe(true);
    expect(r2.buckets.public.has(bKeys.legacyKey)).toBe(true);
    expect(objectsOf(r2, b)).toHaveLength(6);
    expect(clerk.live.has(b)).toBe(true);
    // Enumeration scope: only A's exact prefixes were listed.
    expect(r2.calls.filter((c) => c.op === 'listLegacy').map((c) => c.arg)).toEqual([`${a}/`, `${a}/`]); // storage + storage_sweep
    expect(r2.calls.filter((c) => c.op === 'listPrivate').map((c) => c.arg)).toEqual([privatePrefix(a), privatePrefix(a)]);
    expect(r2.calls.filter((c) => c.op.startsWith('remove')).every((c) => c.arg.startsWith(privatePrefix(a)) || c.arg.startsWith(`${a}/`))).toBe(true);

    passSweepDelay();
    expect(await svc.runJob(a)).toMatchObject({ status: 'deleted', step: 'done' });
    expect(await counts(b)).toEqual(before);
    // The job keeps no user content and no raw id.
    const job = (await db.execute(sql`SELECT * FROM account_deletion_jobs WHERE user_id_hash = ${accountHash(a)}`)).rows[0];
    expect(job).toMatchObject({ status: 'done', step: 'done', user_id: null, last_error_code: null, lease_owner: null });
    expect(JSON.stringify(job)).not.toContain(a);
  });

  it('locks access as soon as the deletion is requested, before any data is deleted', async () => {
    const { a, b } = newPair();
    const r2 = fakeStorage();
    await seed(a, r2);
    const svc = service(r2.storage, fakeIdentity([a]));
    expect(await isAccountDeleting(db, a)).toBe(false);
    expect(await svc.requestDeletion(a)).toBe(true);
    expect(await isAccountDeleting(db, a)).toBe(true);
    expect(await isAccountDeleting(db, b)).toBe(false);
    // A writer that waited on the sync lock is refused once it gets it; another user is not.
    await expect(db.transaction((tx) => lockUserSync(tx, a))).rejects.toMatchObject({ status: 403, code: 'ACCOUNT_DELETING' });
    await expect(db.transaction((tx) => lockUserSync(tx, b))).resolves.toBeUndefined();
    expect(total(await counts(a))).toBeGreaterThan(0); // nothing deleted yet
  });

  it('a duplicate request is a no-op and a repeated DELETE after completion reports deleted', async () => {
    const { a } = newPair();
    const r2 = fakeStorage();
    await seed(a, r2);
    const clerk = fakeIdentity([a]);
    const svc = service(r2.storage, clerk);
    expect(await svc.requestDeletion(a)).toBe(true);
    expect(await svc.requestDeletion(a)).toBe(false);
    expect(await svc.runJob(a)).toMatchObject({ status: 'in_progress', step: 'database_sweep', errorCode: null });
    passSweepDelay();
    expect((await svc.runJob(a)).status).toBe('deleted');
    const again = await deleteAccount(svc, { userId: a, factorVerificationAge: null });
    expect(again).toMatchObject({ status: 'deleted', step: 'done' });
    expect(clerk.calls).toEqual([a]);
  });

  it.each([
    ['storage (R2 listing)', 'storage', 'STORAGE_FAILED'],
    ['database (Postgres)', 'database', 'DATABASE_FAILED'],
    ['identity (Clerk)', 'identity', 'IDENTITY_FAILED'],
  ] as const)('a %s failure leaves the job locked and resumable; the retry completes it', async (_label, step, code) => {
    const { a, b } = newPair();
    const r2 = fakeStorage();
    await seed(a, r2);
    await seed(b, r2);
    const clerk = fakeIdentity([a, b]);
    let failDb = step === 'database';
    const flakyDb = new Proxy(db, {
      get(target, prop, receiver) {
        if (prop === 'transaction' && failDb) {
          failDb = false;
          return async () => {
            throw new Error('fake Postgres failure');
          };
        }
        return Reflect.get(target, prop, receiver);
      },
    });
    if (step === 'storage') r2.failures.listPrivate = 1;
    if (step === 'identity') clerk.failNext(1);
    const svc = service(r2.storage, clerk, { db: flakyDb });

    const first = await deleteAccount(svc, { userId: a, factorVerificationAge: [0, -1] });
    expect(first).toMatchObject({ status: 'in_progress', step, errorCode: code });
    expect(await isAccountDeleting(db, a)).toBe(true);
    const pending = (await db.execute(sql`SELECT * FROM account_deletion_jobs WHERE user_id_hash = ${accountHash(a)}`)).rows[0];
    expect(pending).toMatchObject({ status: 'pending', step, last_error_code: code, user_id: a, lease_owner: null });

    // Re-calling DELETE resumes from the failed step (no fresh re-authentication needed).
    const second = await deleteAccount(svc, { userId: a, factorVerificationAge: null });
    expect(second).toMatchObject({ status: 'deleted', errorCode: null, finalCleanupPending: true });
    expect(total(await counts(a))).toBe(0);
    expect(objectsOf(r2, a)).toEqual([]);
    expect(clerk.live.has(a)).toBe(false);
    expect(total(await counts(b))).toBeGreaterThan(0);
    expect(clerk.live.has(b)).toBe(true);
  });

  it('a crash after a step ran but before it was recorded re-runs that step idempotently', async () => {
    const { a } = newPair();
    const r2 = fakeStorage();
    await seed(a, r2);
    const clerk = fakeIdentity([a]);
    let crash = true;
    const afterStep = async (step: string) => {
      if (step === 'database' && crash) {
        crash = false;
        throw new Error('function killed');
      }
    };
    const svc = service(r2.storage, clerk, { afterStep });
    expect(await deleteAccount(svc, { userId: a, factorVerificationAge: [0, -1] })).toMatchObject({ step: 'database', errorCode: 'DATABASE_FAILED' });
    expect(total(await counts(a))).toBe(0); // the transaction had committed
    expect(await svc.runJob(a)).toMatchObject({ step: 'database_sweep', errorCode: null });
  });

  it('a concurrent runner does not run steps twice while another holds the lease', async () => {
    const { a } = newPair();
    const r2 = fakeStorage();
    await seed(a, r2);
    const clerk = fakeIdentity([a]);
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const slow = service(r2.storage, clerk, {
      afterStep: async (step) => {
        if (step === 'storage') await gate;
      },
    });
    await slow.requestDeletion(a);
    const running = slow.runJob(a);
    await vi.waitFor(async () => {
      const row = (await db.execute(sql`SELECT lease_owner FROM account_deletion_jobs WHERE user_id_hash = ${accountHash(a)}`)).rows[0];
      expect(row.lease_owner).not.toBeNull();
    });
    const other = await service(r2.storage, clerk).runJob(a);
    expect(other).toMatchObject({ status: 'in_progress', step: 'storage' });
    release();
    expect(await running).toMatchObject({ step: 'database_sweep', errorCode: null });
    expect(clerk.calls).toEqual([a]);
  });

  it('the cron resumes stuck jobs whose lease is free, and only those', async () => {
    const { a, b } = newPair();
    const r2 = fakeStorage();
    await seed(a, r2);
    await seed(b, r2);
    const clerk = fakeIdentity([a, b]);
    clerk.failNext(1);
    const svc = service(r2.storage, clerk);
    expect(await deleteAccount(svc, { userId: a, factorVerificationAge: [0, -1] })).toMatchObject({ status: 'in_progress' });
    // B has a live lease (another runner): the cron must skip it.
    await svc.requestDeletion(b);
    await db.execute(sql`UPDATE account_deletion_jobs SET lease_owner = 'other', lease_expires_at = ${clock + 60 * 60_000} WHERE user_id_hash = ${accountHash(b)}`);

    passSweepDelay();
    const result = await svc.resumePending({ limit: 100 });
    expect(result.deleted).toBeGreaterThanOrEqual(1);
    expect(clerk.live.has(a)).toBe(false);
    expect(clerk.live.has(b)).toBe(true);
    expect(total(await counts(b))).toBeGreaterThan(0);
  });

  it('stores a sanitized error class (never the message) and logs it', async () => {
    const { a } = newPair();
    const r2 = fakeStorage();
    await seed(a, r2);
    class ClerkAPIResponseError extends Error {
      status = 429;
    }
    const lines: string[] = [];
    const svc = service(r2.storage, {
      deleteUser: async (userId) => {
        throw new ClerkAPIResponseError(`rate limited for ${userId}`);
      },
    }, { log: (l) => lines.push(l) });
    expect(await deleteAccount(svc, { userId: a, factorVerificationAge: [0, -1] })).toMatchObject({ errorCode: 'IDENTITY_FAILED' });
    const job = (await db.execute(sql`SELECT last_error_code, last_error_class FROM account_deletion_jobs WHERE user_id_hash = ${accountHash(a)}`)).rows[0];
    expect(job).toEqual({ last_error_code: 'IDENTITY_FAILED', last_error_class: 'ClerkAPIResponseError:429' });
    expect(lines.at(-1)).toMatch(/step=identity error=IDENTITY_FAILED cause=ClerkAPIResponseError:429$/);
    expect(lines.join('\n')).not.toContain(a);
  });

  it('late writers that passed the guard before the lock refuse to write; anything that still lands is swept', async () => {
    const { a, b } = newPair();
    const r2 = fakeStorage();
    await seed(a, r2);
    await seed(b, r2);
    const clerk = fakeIdentity([a, b]);
    const svc = service(r2.storage, clerk);
    expect(await deleteAccount(svc, { userId: a, factorVerificationAge: [0, -1] })).toMatchObject({ finalCleanupPending: true });
    expect(total(await counts(a))).toBe(0);

    // (a) Writers re-check the lock inside their write.
    const report = { id: `late-report-${a}`, payload: { s: 1 }, model: 'm', createdAt: NOW };
    await expect(storeAiReport(db, a, 'weekly', 'late', report)).rejects.toMatchObject({ code: 'ACCOUNT_DELETING' });
    const play = { userId: a, productId: 'gymlog_pro_monthly', purchaseTokenHash: `late-${a}`, state: 'ACTIVE' as const,
      expiresAt: NOW + DAY, autoRenewing: true, verifiedAt: NOW };
    expect(await db.transaction((tx) => upsertPlayInTx(tx, play))).toBe('account_deleting');
    await enqueueMediaDeletion(db, { userId: a, objectKey: mediaObjectKey(a, 'exercise', 'late'), at: NOW });
    expect(total(await counts(a))).toBe(0);
    // Another user is not affected by A's lock.
    await storeAiReport(db, b, 'weekly', 'late', { ...report, id: `late-report-${b}` });
    expect(await db.transaction((tx) => upsertPlayInTx(tx, { ...play, userId: b, purchaseTokenHash: `late-${b}` }))).toBe('bound');

    // (b) A write that raced past the check (simulated: inserted directly) is removed by the final
    // sweep, which waits FINAL_SWEEP_DELAY_MS after the request.
    await db.insert(schema.aiReports).values({ id: `raced-${a}`, userId: a, kind: 'weekly', sourceFingerprint: 'x', payload: {}, model: 'm', createdAt: NOW });
    await db.insert(schema.subscriptionEntitlements).values({ userId: a, productId: 'gymlog_pro_monthly', purchaseTokenHash: `raced-${a}`,
      state: 'ACTIVE', expiresAt: NOW + DAY, autoRenewing: true, lastVerifiedAt: NOW, source: 'play', createdAt: NOW, updatedAt: NOW });
    await db.insert(schema.apiRateBuckets).values({ scope: 'ai', subjectHash: hashSubject(a), windowStart: NOW + 1, count: 1 });
    clock += FINAL_SWEEP_DELAY_MS - 1; // 179.999 s after the request: not yet
    expect(await svc.runJob(a)).toMatchObject({ status: 'in_progress', step: 'database_sweep', errorCode: null });
    expect(total(await counts(a))).toBe(3);
    clock += 1;
    expect(await svc.runJob(a)).toMatchObject({ status: 'deleted', step: 'done' });
    expect(total(await counts(a))).toBe(0);
    expect(total(await counts(b))).toBeGreaterThan(0);
    await db.execute(sql`DELETE FROM subscription_entitlements WHERE purchase_token_hash = ${`late-${b}`}`);
  });
});
