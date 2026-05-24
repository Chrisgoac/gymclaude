import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { setPhoto, removePhoto, getPhoto, getPhotosMap } from '@/lib/repositories/exercise-photos';

beforeEach(async () => {
  await db.exercisePhotos.clear();
});

describe('repo exercise-photos', () => {
  it('setPhoto crea una foto para el ejercicio', async () => {
    const prev = await setPhoto('e1', { url: 'https://r2/a.jpg', key: 'k/a.jpg' });
    expect(prev).toBeUndefined();
    expect((await getPhoto('e1'))?.url).toBe('https://r2/a.jpg');
  });

  it('setPhoto reemplaza (no duplica) y devuelve el key anterior', async () => {
    await setPhoto('e1', { url: 'https://r2/a.jpg', key: 'k/a.jpg' });
    const prevKey = await setPhoto('e1', { url: 'https://r2/b.jpg', key: 'k/b.jpg' });
    expect(prevKey).toBe('k/a.jpg');
    const activas = (await db.exercisePhotos.toArray()).filter((p) => p.deletedAt === null);
    expect(activas).toHaveLength(1);
    expect((await getPhoto('e1'))?.url).toBe('https://r2/b.jpg');
  });

  it('removePhoto soft-delete y devuelve el key', async () => {
    await setPhoto('e1', { url: 'https://r2/a.jpg', key: 'k/a.jpg' });
    const key = await removePhoto('e1');
    expect(key).toBe('k/a.jpg');
    expect(await getPhoto('e1')).toBeUndefined();
  });

  it('getPhotosMap mapea solo las activas por exerciseId', async () => {
    await setPhoto('e1', { url: 'https://r2/a.jpg', key: 'k/a.jpg' });
    await setPhoto('e2', { url: 'https://r2/b.jpg', key: 'k/b.jpg' });
    await removePhoto('e2');
    const map = await getPhotosMap();
    expect(map.get('e1')?.url).toBe('https://r2/a.jpg');
    expect(map.has('e2')).toBe(false);
  });
});
