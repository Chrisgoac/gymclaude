import { describe, it, expect, vi, beforeEach } from 'vitest';

const auth = vi.fn();
vi.mock('@clerk/nextjs/server', () => ({ auth: () => auth() }));
const enqueue = vi.fn(async () => {});
vi.mock('@/db/pool', () => ({ getPoolDb: () => ({ tag: 'db' }) }));
vi.mock('@/lib/api/v1/media/deletions', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/v1/media/deletions')>()),
  enqueueMediaDeletion: (...args: unknown[]) => enqueue(...(args as [])),
}));
vi.mock('@/lib/r2/client', () => ({
  r2Configured: vi.fn(() => true),
  putImage: vi.fn().mockResolvedValue(undefined),
  deleteR2Object: vi.fn().mockResolvedValue(undefined),
  publicUrl: (key: string) => `https://pub.r2.dev/${key}`,
}));

import { POST, DELETE } from '@/app/api/progress-photos/route';
import { deleteR2Object, r2Configured } from '@/lib/r2/client';
import { mediaObjectKey } from '@/lib/api/v1/media/deletions';

beforeEach(() => auth.mockReset());

function reqConFoto(): Request {
  const fd = new FormData();
  fd.append('file', new Blob([new Uint8Array([1, 2, 3])], { type: 'image/jpeg' }), 'foto.jpg');
  return new Request('http://localhost/api/progress-photos', { method: 'POST', body: fd });
}

describe('POST /api/progress-photos', () => {
  it('401 sin sesión', async () => {
    auth.mockResolvedValue({ userId: null });
    expect((await POST(reqConFoto())).status).toBe(401);
  });
  it('sube y devuelve url+key namespaced en /progress/', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await POST(reqConFoto());
    expect(res.status).toBe(200);
    const json = await res.json();
    expect(json.key).toContain('u1/progress/');
    expect(json.url).toBe(`https://pub.r2.dev/${json.key}`);
  });
  it('503 si R2 no está configurado', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    vi.mocked(r2Configured).mockReturnValueOnce(false);
    expect((await POST(reqConFoto())).status).toBe(503);
  });
  it('400 si el tipo no es imagen', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const fd = new FormData();
    fd.append('file', new Blob([new Uint8Array([1])], { type: 'text/plain' }), 'x.txt');
    const res = await POST(new Request('http://localhost/api/progress-photos', { method: 'POST', body: fd }));
    expect(res.status).toBe(400);
  });
  it('413 si la imagen es demasiado grande', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const fd = new FormData();
    fd.append('file', new Blob([new Uint8Array(9 * 1024 * 1024)], { type: 'image/jpeg' }), 'foto.jpg');
    const res = await POST(new Request('http://localhost/api/progress-photos', { method: 'POST', body: fd }));
    expect(res.status).toBe(413);
  });
});

describe('DELETE /api/progress-photos', () => {
  it('401 sin sesión', async () => {
    auth.mockResolvedValue({ userId: null });
    const res = await DELETE(new Request('http://localhost/api/progress-photos', { method: 'DELETE', body: JSON.stringify({ key: 'k' }) }));
    expect(res.status).toBe(401);
  });
  it('403 si el key es de otro usuario', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await DELETE(new Request('http://localhost/api/progress-photos', { method: 'DELETE', body: JSON.stringify({ key: 'otro/progress/x.jpg' }) }));
    expect(res.status).toBe(403);
  });
  it('borra OK un key propio', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await DELETE(new Request('http://localhost/api/progress-photos', { method: 'DELETE', body: JSON.stringify({ key: 'u1/progress/x.jpg' }) }));
    expect(res.status).toBe(200);
  });
});

describe('DELETE /api/progress-photos with a v1 private key', () => {
  const del = (key: string) =>
    DELETE(new Request('http://localhost/api/progress-photos', { method: 'DELETE', body: JSON.stringify({ key }) }));

  it('enqueues the deferred deletion of an owned v1 key instead of 403, even without the public bucket', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    enqueue.mockClear();
    vi.mocked(r2Configured).mockReturnValue(false);
    const key = mediaObjectKey('u1', 'progress', 'abc');
    const res = await del(key);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
    expect(enqueue).toHaveBeenCalledWith({ tag: 'db' }, expect.objectContaining({ userId: 'u1', objectKey: key }));
    expect(deleteR2Object).not.toHaveBeenCalledWith(key);
    vi.mocked(r2Configured).mockReturnValue(true);
  });

  it("still forbids another user's v1 key and a v1 key of the other kind", async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    enqueue.mockClear();
    expect((await del(mediaObjectKey('u2', 'progress', 'abc'))).status).toBe(403);
    expect((await del(mediaObjectKey('u1', 'exercise', 'abc'))).status).toBe(403);
    expect(enqueue).not.toHaveBeenCalled();
  });
});
