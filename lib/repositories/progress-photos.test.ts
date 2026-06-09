import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { addPhoto, listPhotos, deletePhoto } from '@/lib/repositories/progress-photos';

beforeEach(async () => { await db.progressPhotos.clear(); });

describe('progress-photos repo', () => {
  it('addPhoto crea una foto con los campos dados', async () => {
    const f = await addPhoto({ url: 'u', key: 'k', fecha: 1000, angulo: 'frente', nota: 'hola' });
    expect(f.id).toBeTruthy();
    expect(f.deletedAt).toBeNull();
    const todas = await listPhotos();
    expect(todas).toHaveLength(1);
    expect(todas[0]).toMatchObject({ url: 'u', key: 'k', fecha: 1000, angulo: 'frente', nota: 'hola' });
  });

  it('listPhotos ordena por fecha desc y excluye tombstones', async () => {
    await addPhoto({ url: 'a', key: 'ka', fecha: 1000, angulo: 'frente', nota: null });
    const b = await addPhoto({ url: 'b', key: 'kb', fecha: 3000, angulo: 'lado', nota: null });
    await addPhoto({ url: 'c', key: 'kc', fecha: 2000, angulo: 'espalda', nota: null });
    await deletePhoto(b.id);
    const todas = await listPhotos();
    expect(todas.map((p) => p.fecha)).toEqual([2000, 1000]);
  });

  it('deletePhoto marca tombstone y devuelve el key', async () => {
    const f = await addPhoto({ url: 'u', key: 'mykey', fecha: 1, angulo: 'frente', nota: null });
    const key = await deletePhoto(f.id);
    expect(key).toBe('mykey');
    expect(await listPhotos()).toHaveLength(0);
  });
});
