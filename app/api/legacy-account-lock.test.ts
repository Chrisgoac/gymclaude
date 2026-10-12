// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => ({ deleting: vi.fn(), pool: vi.fn(), neon: vi.fn(), r2: vi.fn(), lock: vi.fn(), push: vi.fn() }));
vi.mock('@clerk/nextjs/server', () => ({ auth: async () => ({ userId: 'user_1' }) }));
vi.mock('@/lib/account/access', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/account/access')>()),
  legacyDeletingResponse: (u: string) => h.deleting(u),
}));
// Nothing past the guard may run for an account being deleted.
vi.mock('@/db/pool', () => ({ getPoolDb: () => h.pool() }));
vi.mock('@/db/client', () => ({ db: new Proxy({}, { get: () => h.neon() }) }));
vi.mock('@/lib/api/v1/sync/lock', () => ({ lockUserSync: (...a: unknown[]) => h.lock(...a) }));
vi.mock('@/lib/api/v1/sync/legacy', () => ({ applyLegacyPush: (...a: unknown[]) => h.push(...a) }));
const model = vi.hoisted(() => vi.fn());
vi.mock('@/lib/coach-model', () => ({ modeloCoach: model, deepseekConfigured: () => { model(); return true; } }));
vi.mock('ai', () => ({ streamText: model, generateObject: model, jsonSchema: model, generateText: model, convertToModelMessages: model, Output: { object: model } }));
vi.mock('@/lib/r2/client', () => ({ r2Configured: () => true, putImage: h.r2, deleteR2Object: h.r2, publicUrl: (k: string) => k }));

import { accountDeleting } from '@/lib/account/access';
import { GET as legacyPull } from './sync/pull/route';
import { POST as legacyPush } from './sync/push/route';
import { POST as uploadExercise, DELETE as deleteExercise } from './exercise-photos/route';
import { POST as coach } from './coach/route';
import { POST as coachMesocycle } from './coach/mesociclo/route';
import { POST as uploadProgress, DELETE as deleteProgress } from './progress-photos/route';

beforeEach(() => {
  vi.clearAllMocks();
  h.deleting.mockResolvedValue(new Response('ACCOUNT_DELETING', { status: 403 }));
});

describe('legacy PWA routes while the account is being deleted', () => {
  it.each([
    ['GET /api/sync/pull', () => legacyPull(new Request('http://x/api/sync/pull'))],
    ['POST /api/sync/push', () => legacyPush(new Request('http://x/api/sync/push', { method: 'POST', body: '[]' }))],
    ['POST /api/exercise-photos', () => uploadExercise(new Request('http://x/api/exercise-photos', { method: 'POST', body: new FormData() }))],
    ['DELETE /api/exercise-photos', () => deleteExercise(new Request('http://x/api/exercise-photos', { method: 'DELETE', body: '{"key":"user_1/a"}' }))],
    ['POST /api/progress-photos', () => uploadProgress(new Request('http://x/api/progress-photos', { method: 'POST', body: new FormData() }))],
    ['DELETE /api/progress-photos', () => deleteProgress(new Request('http://x/api/progress-photos', { method: 'DELETE', body: '{"key":"user_1/a"}' }))],
    ['POST /api/coach', () => coach(new Request('http://x/api/coach', { method: 'POST', body: '{}' }))],
    ['POST /api/coach/mesociclo', () => coachMesocycle(new Request('http://x/api/coach/mesociclo', { method: 'POST', body: '{}' }))],
  ])('%s answers 403 ACCOUNT_DELETING and touches no storage', async (_name, call) => {
    const res = await call();
    expect(res.status).toBe(403);
    expect(await res.text()).toBe('ACCOUNT_DELETING');
    expect(h.deleting).toHaveBeenCalledWith('user_1');
    expect(h.pool).not.toHaveBeenCalled();
    expect(h.neon).not.toHaveBeenCalled();
    expect(h.r2).not.toHaveBeenCalled();
    expect(model).not.toHaveBeenCalled();
  });
});

describe('legacy push racing a deletion request', () => {
  it('a push that passed the guard but is refused by the sync lock answers 403, writing nothing', async () => {
    h.deleting.mockResolvedValue(null);
    h.pool.mockReturnValue({ transaction: async (fn: (tx: unknown) => Promise<unknown>) => fn({}) });
    h.lock.mockRejectedValue(accountDeleting());
    const res = await legacyPush(new Request('http://x/api/sync/push', { method: 'POST', body: '[]' }));
    expect(res.status).toBe(403);
    expect(await res.text()).toBe('ACCOUNT_DELETING');
    expect(h.push).not.toHaveBeenCalled();
  });
});
