// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AccountDeletionService } from '@/lib/account/deletion';

const h = vi.hoisted(() => ({
  session: { userId: null as string | null, factorVerificationAge: null as [number, number] | null },
  service: undefined as unknown,
  rate: vi.fn(async () => {}),
}));
vi.mock('@clerk/nextjs/server', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@clerk/nextjs/server')>()),
  auth: async () => h.session,
}));
vi.mock('@/lib/api/v1/rate-limit', () => ({ enforceRateLimit: () => h.rate() }));
vi.mock('@/lib/account/runtime', () => ({ getAccountDeletionService: () => h.service }));

import { requestAccountDeletion } from './actions';

function service(existing: 'none' | 'pending' = 'none') {
  return {
    findJob: vi.fn(async () =>
      existing === 'none' ? null : { userIdHash: 'h', status: 'pending' as const, step: 'identity' as const, attempts: 1, lastErrorCode: null },
    ),
    readActiveSubscription: vi.fn(async () => null),
    requestDeletion: vi.fn(async () => true),
    runJob: vi.fn(async () => ({ status: 'deleted' as const, step: 'done' as const, errorCode: null })),
    resumePending: vi.fn(),
  } satisfies AccountDeletionService;
}

beforeEach(() => {
  h.session = { userId: 'user_1', factorVerificationAge: [1, -1] };
  h.service = service();
  h.rate.mockReset().mockResolvedValue(undefined);
});

describe('requestAccountDeletion (server action)', () => {
  it('requires a session', async () => {
    h.session = { userId: null, factorVerificationAge: null };
    expect(await requestAccountDeletion('ELIMINAR')).toEqual({ status: 'error', code: 'AUTH_REQUIRED' });
  });

  it('requires the typed confirmation (it is reachable by a direct POST)', async () => {
    expect(await requestAccountDeletion('eliminar')).toEqual({ status: 'error', code: 'CONFIRMATION_REQUIRED' });
    expect((h.service as AccountDeletionService).requestDeletion).not.toHaveBeenCalled();
  });

  it('asks Clerk to reverify the first factor when the sign-in is not recent, locking nothing', async () => {
    h.session = { userId: 'user_1', factorVerificationAge: [25, -1] };
    const result = await requestAccountDeletion('ELIMINAR');
    // Clerk's reverification hint: useReverification() opens the modal and retries the action.
    expect(result).toMatchObject({ clerk_error: { type: 'forbidden', reason: 'reverification-error', metadata: { reverification: { level: 'first_factor', afterMinutes: 10 } } } });
    expect((h.service as AccountDeletionService).requestDeletion).not.toHaveBeenCalled();
  });

  it('deletes with a recent sign-in', async () => {
    expect(await requestAccountDeletion('ELIMINAR')).toEqual({ status: 'deleted' });
    expect((h.service as AccountDeletionService).requestDeletion).toHaveBeenCalledWith('user_1');
  });

  it('resumes a pending deletion without reverification', async () => {
    h.service = service('pending');
    h.session = { userId: 'user_1', factorVerificationAge: null };
    expect(await requestAccountDeletion('ELIMINAR')).toEqual({ status: 'deleted' });
  });

  it('maps unavailable storage to a generic retry message', async () => {
    h.service = { ...service(), findJob: vi.fn(async () => { throw new Error('db down'); }) };
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(await requestAccountDeletion('ELIMINAR')).toEqual({ status: 'error', code: 'UNAVAILABLE' });
    expect(errors).toHaveBeenCalledWith('[delete-account] request failed', 'unexpected');
    errors.mockRestore();
  });
});
