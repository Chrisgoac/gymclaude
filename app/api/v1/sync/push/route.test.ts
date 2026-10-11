import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiErrorBodySchema, PushResponseSchema, type PushRequest } from '@/lib/api/v1/contracts/sync';
import { setAuthSourceForTests } from '@/lib/api/v1/auth';
import { ApiError, RateLimitedError } from '@/lib/api/v1/errors';

const calls: string[] = [];
const fakeTx = { tag: 'tx' };
const transaction = vi.fn(async (...args: [fn: (tx: unknown) => Promise<unknown>, config?: unknown]) => {
  calls.push('begin');
  return args[0](fakeTx);
});
vi.mock('@/db/pool', () => ({ getPoolDb: () => ({ transaction }) }));
vi.mock('@/lib/api/v1/rate-limit', () => ({ enforceRateLimit: vi.fn(async () => { calls.push('rate'); }) }));
vi.mock('@/lib/api/v1/sync/lock', () => ({ lockUserSync: vi.fn(async () => { calls.push('lock'); }) }));
vi.mock('@/lib/api/v1/sync/push', () => ({
  pushMutations: vi.fn(async () => {
    calls.push('push');
    return { acceptedMutationIds: [MUTATION_ID], conflicts: [] };
  }),
}));

import { MAX_PUSH_BODY_BYTES } from '@/lib/api/v1/sync/limits';
import { POST } from './route';
import { enforceRateLimit } from '@/lib/api/v1/rate-limit';
import { lockUserSync } from '@/lib/api/v1/sync/lock';
import { pushMutations } from '@/lib/api/v1/sync/push';

const MUTATION_ID = '6f1c1c4e-2b0c-4a59-9a43-6d6c2f0f6a11';
const validBody: PushRequest = {
  mutations: [
    {
      mutationId: MUTATION_ID,
      deviceId: 'pixel-8',
      operations: [
        {
          table: 'gyms',
          baseServerVersion: null,
          record: { id: 'gym-1', updatedAt: 1, deletedAt: null, nombre: 'G', orden: 0, archivada: false },
        },
      ],
    },
  ],
} as PushRequest;

const post = (body: BodyInit | null, headers: Record<string, string> = { 'content-type': 'application/json' }) =>
  POST(new Request('http://x/api/v1/sync/push', { method: 'POST', headers, body }));
const errorOf = async (res: Response) => ApiErrorBodySchema.parse(await res.json()).error;

beforeEach(() => {
  calls.length = 0;
  setAuthSourceForTests(async () => ({ userId: 'user_1' }));
});
afterEach(() => {
  setAuthSourceForTests(null);
  vi.clearAllMocks();
});

describe('POST /api/v1/sync/push', () => {
  it('rate-limits, locks the user and delegates the parsed request inside a READ COMMITTED tx', async () => {
    const res = await post(JSON.stringify(validBody));
    expect(res.status).toBe(200);
    expect(PushResponseSchema.parse(await res.json())).toEqual({ acceptedMutationIds: [MUTATION_ID], conflicts: [] });
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(res.headers.get('x-request-id')).toBeTruthy();
    expect(calls).toEqual(['rate', 'begin', 'lock', 'push']);
    expect(enforceRateLimit).toHaveBeenCalledWith('sync', 'user_1');
    expect(transaction.mock.calls[0][1]).toEqual({ isolationLevel: 'read committed' });
    expect(lockUserSync).toHaveBeenCalledWith(fakeTx, 'user_1');
    expect(pushMutations).toHaveBeenCalledWith(fakeTx, { userId: 'user_1' }, validBody);
  });

  it('accepts a charset parameter on the content type', async () => {
    const res = await post(JSON.stringify(validBody), { 'content-type': 'Application/JSON; charset=utf-8' });
    expect(res.status).toBe(200);
  });

  it('401 AUTH_REQUIRED without a bearer session, before any work', async () => {
    setAuthSourceForTests(async () => ({ userId: null }));
    const res = await post(JSON.stringify(validBody));
    expect(res.status).toBe(401);
    expect((await errorOf(res)).code).toBe('AUTH_REQUIRED');
    expect(calls).toEqual([]);
  });

  it('429 with Retry-After when the rate limit trips, before reading the body', async () => {
    vi.mocked(enforceRateLimit).mockRejectedValueOnce(new RateLimitedError(9));
    const res = await post(JSON.stringify(validBody));
    expect(res.status).toBe(429);
    expect(res.headers.get('retry-after')).toBe('9');
    expect((await errorOf(res)).code).toBe('RATE_LIMITED');
    expect(transaction).not.toHaveBeenCalled();
  });

  it('415 UNSUPPORTED_MEDIA_TYPE for non-JSON content types', async () => {
    for (const headers of [{ 'content-type': 'text/plain' }, {} as Record<string, string>]) {
      const res = await post(JSON.stringify(validBody), headers);
      expect(res.status).toBe(415);
      expect(await errorOf(res)).toMatchObject({ code: 'UNSUPPORTED_MEDIA_TYPE', retryable: false });
    }
    expect(transaction).not.toHaveBeenCalled();
  });

  it('413 PAYLOAD_TOO_LARGE when the declared content-length exceeds the cap', async () => {
    const res = await post('{}', {
      'content-type': 'application/json',
      'content-length': String(MAX_PUSH_BODY_BYTES + 1),
    });
    expect(res.status).toBe(413);
    expect((await errorOf(res)).code).toBe('PAYLOAD_TOO_LARGE');
  });

  it('413 PAYLOAD_TOO_LARGE when the streamed body exceeds the cap without a content-length', async () => {
    const chunk = new Uint8Array(64 * 1024).fill(0x20);
    let sent = 0;
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (sent > MAX_PUSH_BODY_BYTES) return controller.close();
        sent += chunk.byteLength;
        controller.enqueue(chunk);
      },
    });
    const res = await POST(
      new Request('http://x/api/v1/sync/push', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: stream,
        duplex: 'half',
      } as RequestInit),
    );
    expect(res.status).toBe(413);
    expect(transaction).not.toHaveBeenCalled();
  });

  it('400 INVALID_JSON for a malformed body', async () => {
    const res = await post('{"mutations": [');
    expect(res.status).toBe(400);
    expect(await errorOf(res)).toMatchObject({ code: 'INVALID_JSON', retryable: false });
  });

  it('400 INVALID_JSON for a body that is not valid UTF-8', async () => {
    const res = await post(new Uint8Array([0x7b, 0x22, 0xff, 0x22, 0x3a, 0x31, 0x7d]));
    expect(res.status).toBe(400);
    expect(await errorOf(res)).toMatchObject({ code: 'INVALID_JSON', retryable: false });
    expect(transaction).not.toHaveBeenCalled();
  });

  it('400 INVALID_REQUEST for duplicate mutationIds in one request', async () => {
    const res = await post(JSON.stringify({ mutations: [validBody.mutations[0], validBody.mutations[0]] }));
    expect(res.status).toBe(400);
    expect((await errorOf(res)).code).toBe('INVALID_REQUEST');
    expect(transaction).not.toHaveBeenCalled();
  });

  it('400 INVALID_REQUEST when the body fails PushRequestSchema', async () => {
    for (const body of [{}, { mutations: [] }, { mutations: [{ ...validBody.mutations[0], mutationId: 'nope' }] }]) {
      const res = await post(JSON.stringify(body));
      expect(res.status).toBe(400);
      expect((await errorOf(res)).code).toBe('INVALID_REQUEST');
    }
    expect(transaction).not.toHaveBeenCalled();
  });

  it('400 INVALID_REQUEST for server-only Pro/AI tables, before any transaction', async () => {
    for (const table of ['subscriptionEntitlements', 'subscription_entitlements', 'aiReports', 'ai_reports',
      'reportedAiResponses', 'reported_ai_responses', 'aiResponses', 'ai_responses']) {
      const op = { ...validBody.mutations[0].operations[0], table };
      const res = await post(JSON.stringify({ mutations: [{ ...validBody.mutations[0], operations: [op] }] }));
      expect(res.status, table).toBe(400);
      expect((await errorOf(res)).code).toBe('INVALID_REQUEST');
    }
    expect(transaction).not.toHaveBeenCalled();
    expect(pushMutations).not.toHaveBeenCalled();
  });

  it('passes retryable ApiErrors from the service through as stable JSON', async () => {
    vi.mocked(pushMutations).mockRejectedValueOnce(
      new ApiError(409, 'MUTATION_IN_FLIGHT', 'Mutation is being processed', true, { mutationId: [MUTATION_ID] }),
    );
    const res = await post(JSON.stringify(validBody));
    expect(res.status).toBe(409);
    expect(await errorOf(res)).toEqual({
      code: 'MUTATION_IN_FLIGHT',
      message: 'Mutation is being processed',
      retryable: true,
      details: { mutationId: [MUTATION_ID] },
    });
  });

  it.each(['40P01', '40001'])('maps PostgreSQL %s (wrapped by Drizzle) to 503 RETRY_LATER', async (code) => {
    const pgError = Object.assign(new Error('pg says no'), { code });
    vi.mocked(pushMutations).mockRejectedValueOnce(new Error('Failed query', { cause: pgError }));
    const res = await post(JSON.stringify(validBody));
    expect(res.status).toBe(503);
    expect(await errorOf(res)).toMatchObject({ code: 'RETRY_LATER', retryable: true });
  });
});
