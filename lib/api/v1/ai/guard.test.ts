// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { ApiError } from '@/lib/api/v1/errors';
import { errorResponse } from '@/lib/api/v1/response';
import { ApiErrorBodySchema } from '@/lib/api/v1/contracts/common';
import type { EntitlementReader } from '@/lib/entitlements/reader';
import { requirePro } from './guard';

const NOW = 1_767_225_600_000;
const principal = { userId: 'user_1' };
const readerOf = (impl: EntitlementReader['isPro']): EntitlementReader => ({ isPro: vi.fn(impl) });

async function apiErrorOf(p: Promise<unknown>): Promise<ApiError> {
  try {
    await p;
  } catch (e) {
    expect(e).toBeInstanceOf(ApiError);
    return e as ApiError;
  }
  throw new Error('expected an ApiError');
}

describe('requirePro', () => {
  let consoleError: MockInstance<typeof console.error>;
  beforeEach(() => {
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
  });
  afterEach(() => consoleError.mockRestore());

  it('passes for a Pro user and asks the reader about the principal only, at the given instant', async () => {
    const reader = readerOf(async () => true);
    await expect(requirePro(principal, { reader, now: () => NOW })).resolves.toBeUndefined();
    expect(reader.isPro).toHaveBeenCalledExactlyOnceWith('user_1', NOW);
  });

  it('403 PRO_REQUIRED (not retryable) without an active entitlement', async () => {
    const err = await apiErrorOf(requirePro(principal, { reader: readerOf(async () => false), now: () => NOW }));
    expect(err).toMatchObject({ status: 403, code: 'PRO_REQUIRED', retryable: false });
  });

  it('fails closed with 503 ENTITLEMENT_UNAVAILABLE (retryable) when the store errors', async () => {
    const err = await apiErrorOf(
      requirePro(principal, { reader: readerOf(async () => { throw new Error('ECONNREFUSED 10.0.0.1'); }), now: () => NOW }),
    );
    expect(err).toMatchObject({ status: 503, code: 'ENTITLEMENT_UNAVAILABLE', retryable: true });
    // The wire body never leaks the underlying error.
    const body = ApiErrorBodySchema.parse(await errorResponse(err, 'req-12345678').json());
    expect(JSON.stringify(body)).not.toContain('ECONNREFUSED');
    // The operational log records the outage without the raw error message.
    expect(consoleError).toHaveBeenCalledOnce();
    expect(JSON.stringify(consoleError.mock.calls)).not.toContain('ECONNREFUSED');
  });

  it('fails closed when the reader returns something other than a boolean', async () => {
    const err = await apiErrorOf(
      requirePro(principal, { reader: readerOf(async () => 'yes' as unknown as boolean), now: () => NOW }),
    );
    expect(err.code).toBe('ENTITLEMENT_UNAVAILABLE');
  });

  it('with the default reader and no database, fails closed rather than granting', async () => {
    const saved = process.env.DATABASE_URL;
    delete process.env.DATABASE_URL;
    try {
      // getPoolDb() is resolved per call, so a missing database rejects instead of granting.
      const err = await apiErrorOf(requirePro(principal));
      expect(err.code).toBe('ENTITLEMENT_UNAVAILABLE');
    } finally {
      if (saved !== undefined) process.env.DATABASE_URL = saved;
    }
  });
});
