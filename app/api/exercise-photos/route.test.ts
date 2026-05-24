import { describe, it, expect, vi, beforeEach } from 'vitest';

const auth = vi.fn();
vi.mock('@clerk/nextjs/server', () => ({ auth: () => auth() }));
vi.mock('@/lib/r2/client', () => ({
  r2Configured: vi.fn(() => true),
  putImage: vi.fn().mockResolvedValue(undefined),
  deleteR2Object: vi.fn().mockResolvedValue(undefined),
  publicUrl: (key: string) => `https://pub.r2.dev/${key}`,
}));

import { POST, DELETE } from '@/app/api/exercise-photos/route';
import { r2Configured } from '@/lib/r2/client';

beforeEach(() => auth.mockReset());

function reqConFoto(exerciseId: string): Request {
  const fd = new FormData();
  fd.append('exerciseId', exerciseId);
  fd.append('file', new Blob([new Uint8Array([1, 2, 3])], { type: 'image/jpeg' }), 'foto.jpg');
  return new Request('http://localhost/api/exercise-photos', { method: 'POST', body: fd });
}

describe('POST /api/exercise-photos', () => {
  it('401 sin sesión', async () => {
    auth.mockResolvedValue({ userId: null });
    const res = await POST(reqConFoto('e1'));
    expect(res.status).toBe(401);
  });

  it('sube y devuelve url+key con sesión', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await POST(reqConFoto('e1'));
    expect(res.status).toBe(200);
    const json = await res.json();
    expect(json.key).toContain('u1/e1/');
    expect(json.url).toBe(`https://pub.r2.dev/${json.key}`);
  });

  it('503 si R2 no está configurado', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    vi.mocked(r2Configured).mockReturnValueOnce(false);
    const res = await POST(reqConFoto('e1'));
    expect(res.status).toBe(503);
  });

  it('413 si la imagen es demasiado grande', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const fd = new FormData();
    fd.append('exerciseId', 'e1');
    fd.append('file', new Blob([new Uint8Array(9 * 1024 * 1024)], { type: 'image/jpeg' }), 'foto.jpg');
    const res = await POST(new Request('http://localhost/api/exercise-photos', { method: 'POST', body: fd }));
    expect(res.status).toBe(413);
  });
});

describe('DELETE /api/exercise-photos', () => {
  it('401 sin sesión', async () => {
    auth.mockResolvedValue({ userId: null });
    const res = await DELETE(new Request('http://localhost/api/exercise-photos', {
      method: 'DELETE', body: JSON.stringify({ key: 'k' }),
    }));
    expect(res.status).toBe(401);
  });

  it('403 si el key es de otro usuario', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await DELETE(new Request('http://localhost/api/exercise-photos', {
      method: 'DELETE', body: JSON.stringify({ key: 'otro/e1/x.jpg' }),
    }));
    expect(res.status).toBe(403);
  });
});
