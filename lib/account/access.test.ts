// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => ({ execute: vi.fn() }));
vi.mock('@/db/pool', () => ({ getPoolDb: () => ({ execute: h.execute }) }));

import { accountHash, assertAccountActive, isAccountDeleting, legacyDeletingResponse } from './access';

const db = (result: unknown) => ({ execute: vi.fn(async () => result) }) as never;
const failing = (code: string) =>
  ({ execute: vi.fn(async () => { throw Object.assign(new Error('pg'), { cause: Object.assign(new Error('inner'), { code }) }); }) }) as never;

describe('account access lock', () => {
  it('hashes the user id with a domain prefix (no raw id in the jobs table)', () => {
    expect(accountHash('user_1')).toMatch(/^[0-9a-f]{64}$/);
    expect(accountHash('user_1')).not.toBe(accountHash('user_2'));
  });

  it('is locked exactly when a deletion job row exists', async () => {
    expect(await isAccountDeleting(db({ rows: [{ deleting: 1 }] }), 'u')).toBe(true);
    expect(await isAccountDeleting(db({ rows: [] }), 'u')).toBe(false);
  });

  it('treats a missing table (before migration 0005) as not locked, and fails closed on other errors', async () => {
    expect(await isAccountDeleting(failing('42P01'), 'u')).toBe(false);
    await expect(isAccountDeleting(failing('57P01'), 'u')).rejects.toThrow();
  });

  it('assertAccountActive throws 403 ACCOUNT_DELETING (non-retryable)', async () => {
    await expect(assertAccountActive('u', db({ rows: [{}] }))).rejects.toMatchObject({
      status: 403, code: 'ACCOUNT_DELETING', retryable: false,
    });
    await expect(assertAccountActive('u', db({ rows: [] }))).resolves.toBeUndefined();
  });

  it('legacy routes get a plain-text 403 or null', async () => {
    h.execute.mockResolvedValueOnce({ rows: [{}] });
    const res = await legacyDeletingResponse('u');
    expect(res?.status).toBe(403);
    expect(await res?.text()).toBe('ACCOUNT_DELETING');
    h.execute.mockResolvedValueOnce({ rows: [] });
    expect(await legacyDeletingResponse('u')).toBeNull();
  });
});
