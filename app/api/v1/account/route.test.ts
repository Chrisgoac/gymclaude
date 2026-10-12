// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAuthSourceForTests } from '@/lib/api/v1/auth';
import { ApiErrorBodySchema } from '@/lib/api/v1/contracts/common';
import { RateLimitedError } from '@/lib/api/v1/errors';
import type { AccountDeletionService, ActiveSubscription, DeletionJob, DeletionOutcome } from '@/lib/account/deletion';

const h = vi.hoisted(() => ({
  service: undefined as unknown,
  rate: vi.fn<(...args: unknown[]) => Promise<void>>(async () => {}),
  guard: vi.fn<(userId: string) => Promise<void>>(async () => {}),
  unavailable: false,
}));
vi.mock('@/lib/api/v1/rate-limit', () => ({ enforceRateLimit: (...a: unknown[]) => h.rate(...a) }));
vi.mock('@/lib/account/access', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/account/access')>()),
  assertAccountActive: (userId: string) => h.guard(userId),
}));
vi.mock('@/lib/account/runtime', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/account/runtime')>();
  return {
    ...actual,
    getAccountDeletionService: () => {
      if (h.unavailable) throw actual.deletionUnavailable();
      return h.service;
    },
  };
});

import { DELETE } from './route';
import { withApiRoute } from '@/lib/api/v1/route';
import { accountDeleting } from '@/lib/account/access';

const USER = 'user_1';
const SUB: ActiveSubscription = {
  productId: 'gymlog_pro_monthly', state: 'ACTIVE', validUntil: 1_800_000_000_000, willRenew: true,
  manageUrl: 'https://play.google.com/store/account/subscriptions?package=com.chrisgoac.gymlog&sku=gymlog_pro_monthly',
};

/** In-memory service: one job per user, scripted outcomes for runJob. */
function fakeService(outcomes: DeletionOutcome[] = [{ status: 'deleted', step: 'done', errorCode: null }]) {
  const jobs = new Map<string, DeletionJob>();
  const service = {
    findJob: vi.fn(async (u: string) => jobs.get(u) ?? null),
    readActiveSubscription: vi.fn(async () => SUB),
    requestDeletion: vi.fn(async (u: string) => {
      if (jobs.has(u)) return false;
      jobs.set(u, { userIdHash: 'h', status: 'pending', step: 'storage', attempts: 0, lastErrorCode: null });
      return true;
    }),
    runJob: vi.fn(async (u: string) => {
      const outcome = outcomes.shift() ?? { status: 'deleted' as const, step: 'done' as const, errorCode: null };
      jobs.set(u, { userIdHash: 'h', status: outcome.status === 'deleted' ? 'done' : 'pending', step: outcome.step, attempts: 1, lastErrorCode: outcome.errorCode });
      return outcome;
    }),
    resumePending: vi.fn(),
  } satisfies AccountDeletionService;
  return { service, jobs };
}

const signedIn = (fva: [number, number] | null = [1, -1]) =>
  setAuthSourceForTests(async () => ({ userId: USER, factorVerificationAge: fva }));

const del = (body: unknown = { confirm: 'ELIMINAR' }, headers: Record<string, string> = { 'content-type': 'application/json' }) =>
  DELETE(new Request('http://x/api/v1/account', { method: 'DELETE', headers, body: JSON.stringify(body) }));

async function errorCode(res: Response) {
  return ApiErrorBodySchema.parse(await res.json()).error.code;
}

beforeEach(() => {
  h.rate.mockReset().mockResolvedValue(undefined);
  h.guard.mockReset().mockResolvedValue(undefined);
  h.unavailable = false;
  h.service = fakeService().service;
});
afterEach(() => setAuthSourceForTests(null));

describe('DELETE /api/v1/account', () => {
  it('401 without a session, touching nothing', async () => {
    setAuthSourceForTests(async () => ({ userId: null }));
    const res = await del();
    expect(res.status).toBe(401);
    expect(await errorCode(res)).toBe('AUTH_REQUIRED');
    expect((h.service as AccountDeletionService).requestDeletion).not.toHaveBeenCalled();
  });

  it.each([
    ['missing confirmation', {}],
    ['lowercase confirmation', { confirm: 'eliminar' }],
    ['extra writable fields', { confirm: 'ELIMINAR', userId: 'user_2' }],
  ])('400 on %s, without locking the account', async (_l, body) => {
    signedIn();
    const res = await del(body);
    expect(res.status).toBe(400);
    expect(await errorCode(res)).toBe('INVALID_REQUEST');
    expect((h.service as AccountDeletionService).requestDeletion).not.toHaveBeenCalled();
  });

  it('415 on a non-JSON body', async () => {
    signedIn();
    const res = await del({ confirm: 'ELIMINAR' }, { 'content-type': 'text/plain' });
    expect(res.status).toBe(415);
  });

  it.each([
    ['a stale first factor (11 min)', [11, -1]],
    ['a token without the fva claim', null],
  ] as const)('403 REAUTH_REQUIRED with %s, without locking the account', async (_l, fva) => {
    signedIn(fva as [number, number] | null);
    const res = await del();
    expect(res.status).toBe(403);
    expect(await errorCode(res)).toBe('REAUTH_REQUIRED');
    expect((h.service as AccountDeletionService).requestDeletion).not.toHaveBeenCalled();
  });

  it('200 deleted with the active subscription warning and a Play management link', async () => {
    signedIn();
    const res = await del();
    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(res.headers.get('x-request-id')).toBeTruthy();
    expect(await res.json()).toEqual({ status: 'deleted', step: 'done', retryable: false, finalCleanupPending: false, activeSubscription: SUB });
    expect(h.rate).toHaveBeenCalledWith('account', USER);
    const svc = h.service as AccountDeletionService;
    expect(svc.requestDeletion).toHaveBeenCalledWith(USER);
    // The warning is read before anything is deleted.
    expect((svc.readActiveSubscription as ReturnType<typeof vi.fn>).mock.invocationCallOrder[0]).toBeLessThan(
      (svc.runJob as ReturnType<typeof vi.fn>).mock.invocationCallOrder[0],
    );
  });

  it('202 in_progress when a subsystem fails; a later DELETE (even with a stale session) resumes it', async () => {
    const { service } = fakeService([{ status: 'in_progress', step: 'identity', errorCode: 'IDENTITY_FAILED' }]);
    h.service = service;
    signedIn();
    const first = await del();
    expect(first.status).toBe(202);
    expect(await first.json()).toEqual({
      status: 'in_progress', step: 'identity', retryable: true, errorCode: 'IDENTITY_FAILED', finalCleanupPending: false, activeSubscription: SUB,
    });

    signedIn([45, -1]); // already confirmed: resuming needs no fresh authentication
    const second = await del();
    expect(second.status).toBe(200);
    expect(service.requestDeletion).toHaveBeenCalledTimes(1);
    expect(service.runJob).toHaveBeenCalledTimes(2);
  });

  it('200 deleted (finalCleanupPending) when only the delayed final sweep remains', async () => {
    h.service = fakeService([{ status: 'in_progress', step: 'database_sweep', errorCode: null }]).service;
    signedIn();
    const res = await del();
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({
      status: 'deleted', step: 'database_sweep', retryable: false, finalCleanupPending: true, activeSubscription: SUB,
    });
  });

  it('a repeated DELETE after completion answers deleted without running again', async () => {
    const { service } = fakeService();
    h.service = service;
    signedIn();
    expect((await del()).status).toBe(200);
    signedIn(null);
    const again = await del();
    expect(again.status).toBe(200);
    expect(await again.json()).toMatchObject({ status: 'deleted' });
    expect(service.runJob).toHaveBeenCalledTimes(1);
    // Answering an already completed deletion spends no rate limit (no new bucket row for the user).
    expect(h.rate).toHaveBeenCalledTimes(1);
  });

  it('is not blocked by the ACCOUNT_DELETING guard (it must be able to resume its own deletion)', async () => {
    h.guard.mockRejectedValue(accountDeleting());
    signedIn();
    expect((await del()).status).toBe(200);
    expect(h.guard).not.toHaveBeenCalled();
  });

  it('503 DELETION_UNAVAILABLE (retryable) when storage is not configured, before locking', async () => {
    h.unavailable = true;
    signedIn();
    const res = await del();
    expect(res.status).toBe(503);
    expect(await errorCode(res)).toBe('DELETION_UNAVAILABLE');
  });

  it('429 when rate limited', async () => {
    h.rate.mockRejectedValue(new RateLimitedError(30));
    signedIn();
    const res = await del();
    expect(res.status).toBe(429);
    expect((h.service as AccountDeletionService).requestDeletion).not.toHaveBeenCalled();
  });
});

describe('access lock on every other /api/v1 route', () => {
  it('denies an account being deleted with 403 ACCOUNT_DELETING before the handler runs', async () => {
    h.guard.mockRejectedValue(accountDeleting());
    signedIn();
    const handler = vi.fn(async () => Response.json({ ok: true }));
    const res = await withApiRoute(handler)(new Request('http://x/api/v1/anything'));
    expect(res.status).toBe(403);
    expect(await errorCode(res)).toBe('ACCOUNT_DELETING');
    expect(handler).not.toHaveBeenCalled();
    expect(h.guard).toHaveBeenCalledWith(USER);
  });
});
