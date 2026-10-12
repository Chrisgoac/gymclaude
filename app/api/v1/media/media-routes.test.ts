import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
// The account-deletion access lock has its own tests (app/api/v1/account, lib/account).
vi.mock('@/lib/account/access', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/account/access')>()),
  assertAccountActive: async () => {},
}));
import { setAuthSourceForTests } from '@/lib/api/v1/auth';
import { ApiError, RateLimitedError } from '@/lib/api/v1/errors';
import { ApiErrorBodySchema } from '@/lib/api/v1/contracts/common';
import { MEDIA_LIMITS } from '@/lib/api/v1/media/contracts';
import { MAX_MEDIA_BODY_BYTES } from '@/lib/api/v1/media/http';

const calls: string[] = [];
const service = {
  upload: vi.fn(),
  readGrant: vi.fn(),
  delete: vi.fn(),
  processDeletions: vi.fn(),
};
vi.mock('@/lib/api/v1/rate-limit', () => ({ enforceRateLimit: vi.fn(async () => { calls.push('rate'); }) }));
vi.mock('@/lib/api/v1/media/runtime', () => ({ getMediaService: () => service }));

import { POST as postExercise } from './exercises/route';
import { POST as postProgress } from './progress/route';
import { DELETE as deleteExercise } from './exercises/[id]/route';
import { DELETE as deleteProgress } from './progress/[id]/route';
import { GET as getGrant } from './read/[id]/route';
import { GET as getRedirect } from '@/app/api/media/read/[id]/route';
import { enforceRateLimit } from '@/lib/api/v1/rate-limit';

const SIGNED = 'https://acct.r2.cloudflarestorage.com/bucket/users/h/exercise/k?X-Amz-Signature=secret&X-Amz-Expires=300';
const uploadOk = { mediaId: 'm1', objectKey: 'users/h/exercise/k', readUrl: SIGNED, readUrlExpiresAt: 123 };

const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]);
const form = (fields: Record<string, string>, bytes: Uint8Array = jpeg, type = 'image/jpeg') => {
  const fd = new FormData();
  for (const [k, v] of Object.entries(fields)) fd.append(k, v);
  fd.append('file', new Blob([bytes as BlobPart], { type }), 'p.jpg');
  return fd;
};
const postForm = (handler: typeof postExercise, path: string, fd: FormData, init: RequestInit = {}) =>
  handler(new Request(`http://x/api/v1/media/${path}`, { method: 'POST', body: fd, ...init }));
const errorOf = async (res: Response) => ApiErrorBodySchema.parse(await res.json()).error;

beforeEach(() => {
  calls.length = 0;
  setAuthSourceForTests(async () => ({ userId: 'user_1' }));
  service.upload.mockResolvedValue(uploadOk);
});
afterEach(() => {
  setAuthSourceForTests(null);
  vi.clearAllMocks();
});

describe('POST /api/v1/media/exercises and /progress', () => {
  it('rate-limits then uploads the parsed multipart for the authenticated user', async () => {
    const res = await postForm(postExercise, 'exercises', form({ id: 'm1', exerciseId: 'e1' }));
    expect(res.status).toBe(201);
    expect(await res.json()).toEqual(uploadOk);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(enforceRateLimit).toHaveBeenCalledWith('media', 'user_1');
    const [userId, input] = service.upload.mock.calls[0];
    expect(userId).toBe('user_1');
    expect(input).toMatchObject({ kind: 'exercise', fields: { id: 'm1', exerciseId: 'e1' }, declaredMime: 'image/jpeg' });
    expect([...input.bytes]).toEqual([...jpeg]);
  });

  it('parses progress fields (fecha as a number)', async () => {
    await postForm(postProgress, 'progress', form({ fecha: '1700000000000', angulo: 'frente', nota: 'hola' }));
    expect(service.upload.mock.calls[0][1]).toMatchObject({
      kind: 'progress',
      fields: { fecha: 1700000000000, angulo: 'frente', nota: 'hola' },
    });
  });

  it('401 without a bearer principal, before any work', async () => {
    setAuthSourceForTests(async () => ({ userId: null }));
    const res = await postForm(postExercise, 'exercises', form({ exerciseId: 'e1' }));
    expect(res.status).toBe(401);
    expect((await errorOf(res)).code).toBe('AUTH_REQUIRED');
    expect(enforceRateLimit).not.toHaveBeenCalled();
    expect(service.upload).not.toHaveBeenCalled();
  });

  it('429 with Retry-After when rate limited, before reading the body', async () => {
    vi.mocked(enforceRateLimit).mockRejectedValueOnce(new RateLimitedError(7));
    const res = await postForm(postExercise, 'exercises', form({ exerciseId: 'e1' }));
    expect(res.status).toBe(429);
    expect(res.headers.get('retry-after')).toBe('7');
    expect(service.upload).not.toHaveBeenCalled();
  });

  it('415 for a non multipart content type', async () => {
    const res = await postExercise(
      new Request('http://x/api/v1/media/exercises', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: '{}',
      }),
    );
    expect(res.status).toBe(415);
    expect(service.upload).not.toHaveBeenCalled();
  });

  it('413 from Content-Length alone, without reading any body', async () => {
    const fd = form({ exerciseId: 'e1' });
    const req = new Request('http://x/api/v1/media/exercises', {
      method: 'POST',
      headers: { 'content-length': String(MAX_MEDIA_BODY_BYTES + 1) },
      body: fd,
    });
    const getReader = vi.spyOn(req.body!, 'getReader');
    const res = await postExercise(req);
    expect(res.status).toBe(413);
    expect((await errorOf(res)).code).toBe('MEDIA_TOO_LARGE');
    expect(getReader).not.toHaveBeenCalled();
    expect(service.upload).not.toHaveBeenCalled();
  });

  it('413 from the stream cap when Content-Length is absent or lies, cancelling the read', async () => {
    let pulled = 0;
    const chunk = new Uint8Array(1024 * 1024);
    let cancelled = false;
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        pulled++;
        controller.enqueue(chunk);
        if (pulled > 50) controller.close();
      },
      cancel() {
        cancelled = true;
      },
    });
    const req = new Request('http://x/api/v1/media/exercises', {
      method: 'POST',
      headers: { 'content-type': 'multipart/form-data; boundary=zzz', 'content-length': '10' },
      body: stream,
      duplex: 'half',
    } as RequestInit);
    const res = await postExercise(req);
    expect(res.status).toBe(413);
    expect(cancelled).toBe(true);
    expect(pulled).toBeLessThan(15); // cap = 8 MiB + overhead, not the whole stream
    expect(service.upload).not.toHaveBeenCalled();
  });

  it('accepts a file at the max size (cap includes multipart overhead)', async () => {
    const big = new Uint8Array(MEDIA_LIMITS.maxBytes);
    big.set(jpeg);
    const res = await postForm(postExercise, 'exercises', form({ exerciseId: 'e1' }, big));
    expect(res.status).toBe(201);
    expect(service.upload.mock.calls[0][1].bytes.byteLength).toBe(MEDIA_LIMITS.maxBytes);
  });

  it('does nothing (no R2 upload) when the request is already aborted', async () => {
    const ac = new AbortController();
    ac.abort();
    const res = await postForm(postExercise, 'exercises', form({ exerciseId: 'e1' }), { signal: ac.signal });
    expect(res.status).toBe(499);
    expect((await errorOf(res)).code).toBe('REQUEST_CANCELLED');
    expect(service.upload).not.toHaveBeenCalled();
  });

  it('aborts mid-read and skips the upload', async () => {
    const ac = new AbortController();
    let sent = 0;
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        sent++;
        controller.enqueue(new Uint8Array(1024));
        if (sent === 2) ac.abort();
        if (sent > 5) controller.close();
      },
    });
    const req = new Request('http://x/api/v1/media/exercises', {
      method: 'POST',
      headers: { 'content-type': 'multipart/form-data; boundary=zzz' },
      body: stream,
      signal: ac.signal,
      duplex: 'half',
    } as RequestInit);
    const res = await postExercise(req);
    expect(res.status).toBe(499);
    expect(service.upload).not.toHaveBeenCalled();
  });

  it('400 for a malformed multipart body, a missing file or repeated fields', async () => {
    const bad = await postExercise(
      new Request('http://x/api/v1/media/exercises', {
        method: 'POST',
        headers: { 'content-type': 'multipart/form-data; boundary=zzz' },
        body: 'not multipart',
      }),
    );
    expect(bad.status).toBe(400);
    expect((await errorOf(bad)).code).toBe('INVALID_MULTIPART');

    const noFile = new FormData();
    noFile.append('exerciseId', 'e1');
    expect((await postForm(postExercise, 'exercises', noFile)).status).toBe(400);

    const dup = form({ exerciseId: 'e1' });
    dup.append('exerciseId', 'e2');
    expect((await postForm(postExercise, 'exercises', dup)).status).toBe(400);
    expect(service.upload).not.toHaveBeenCalled();
  });

  it('propagates stable service errors as JSON', async () => {
    service.upload.mockRejectedValueOnce(new ApiError(415, 'UNSUPPORTED_MEDIA_TYPE', 'Media type is not supported', false));
    const res = await postForm(postExercise, 'exercises', form({ exerciseId: 'e1' }));
    expect(res.status).toBe(415);
    expect(await errorOf(res)).toMatchObject({ code: 'UNSUPPORTED_MEDIA_TYPE', retryable: false });
  });

  it('does not leak signed query parameters into logs on a failure', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    service.upload.mockRejectedValueOnce(new Error(`boom ${SIGNED}`));
    const res = await postForm(postExercise, 'exercises', form({ exerciseId: 'e1' }));
    expect(res.status).toBe(500);
    expect(JSON.stringify(await res.json())).not.toContain('X-Amz');
    spy.mockRestore();
  });
});

describe('DELETE /api/v1/media/{exercises,progress}/[id]', () => {
  it.each([
    ['exercises', deleteExercise],
    ['progress', deleteProgress],
  ])('tombstones through the service (%s)', async (path, handler) => {
    service.delete.mockResolvedValue({ mediaId: 'm1', deleted: true });
    const res = await handler(new Request(`http://x/api/v1/media/${path}/m1`, { method: 'DELETE' }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ mediaId: 'm1', deleted: true });
    expect(service.delete).toHaveBeenCalledWith('user_1', 'm1');
    expect(enforceRateLimit).toHaveBeenCalledWith('media', 'user_1');
  });

  it('404 for foreign/unknown media and 401 without auth', async () => {
    service.delete.mockRejectedValueOnce(new ApiError(404, 'MEDIA_NOT_FOUND', 'Media not found', false));
    const res = await deleteExercise(new Request('http://x/api/v1/media/exercises/nope', { method: 'DELETE' }));
    expect(res.status).toBe(404);
    expect((await errorOf(res)).code).toBe('MEDIA_NOT_FOUND');
    setAuthSourceForTests(async () => ({ userId: null }));
    expect((await deleteExercise(new Request('http://x/api/v1/media/exercises/m1', { method: 'DELETE' }))).status).toBe(401);
  });

  it('400 for an invalid id', async () => {
    const res = await deleteExercise(new Request('http://x/api/v1/media/exercises/a%20b', { method: 'DELETE' }));
    expect(res.status).toBe(400);
    expect(service.delete).not.toHaveBeenCalled();
  });
});

describe('GET /api/v1/media/read/[id] (bearer grant)', () => {
  it('returns a fresh no-store signed grant for owned media', async () => {
    service.readGrant.mockResolvedValue({ mediaId: 'm1', readUrl: SIGNED, expiresAt: 999 });
    const res = await getGrant(new Request('http://x/api/v1/media/read/m1'));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ mediaId: 'm1', readUrl: SIGNED, expiresAt: 999 });
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(service.readGrant).toHaveBeenCalledWith('user_1', 'm1');
    expect(enforceRateLimit).toHaveBeenCalledWith('media', 'user_1');
  });

  it('404 for media the user does not own (same as unknown) and 401 unauthenticated', async () => {
    service.readGrant.mockRejectedValueOnce(new ApiError(404, 'MEDIA_NOT_FOUND', 'Media not found', false));
    const res = await getGrant(new Request('http://x/api/v1/media/read/other'));
    expect(res.status).toBe(404);
    expect(JSON.stringify(await res.json())).not.toContain('X-Amz');
    setAuthSourceForTests(async () => ({ userId: null }));
    expect((await getGrant(new Request('http://x/api/v1/media/read/m1'))).status).toBe(401);
  });
});

describe('GET /api/media/read/[id] (PWA redirect)', () => {
  it('302s to the signed URL with private, no-store caching', async () => {
    service.readGrant.mockResolvedValue({ mediaId: 'm1', readUrl: SIGNED, expiresAt: 999 });
    const res = await getRedirect(new Request('http://x/api/media/read/m1'));
    expect(res.status).toBe(302);
    expect(res.headers.get('location')).toBe(SIGNED);
    expect(res.headers.get('cache-control')).toBe('private, no-store');
    expect(await res.text()).toBe('');
    expect(service.readGrant).toHaveBeenCalledWith('user_1', 'm1');
  });

  it('404/401 carry no Location and no signed parameters', async () => {
    service.readGrant.mockRejectedValueOnce(new ApiError(404, 'MEDIA_NOT_FOUND', 'Media not found', false));
    const res = await getRedirect(new Request('http://x/api/media/read/other'));
    expect(res.status).toBe(404);
    expect(res.headers.get('location')).toBeNull();
    setAuthSourceForTests(async () => ({ userId: null }));
    const anon = await getRedirect(new Request('http://x/api/media/read/m1'));
    expect(anon.status).toBe(401);
    expect(anon.headers.get('location')).toBeNull();
  });
});
