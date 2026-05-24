import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import {
  listGyms, createGym, renameGym, archiveGym, softDeleteGym, reorderGyms, getGymsMap,
} from '@/lib/repositories/gyms';

beforeEach(async () => {
  await db.gyms.clear();
  await db.workoutSessions.clear();
});

describe('repo gyms', () => {
  it('crea con orden incremental y lista activos por orden', async () => {
    const a = await createGym('Gold\'s');
    const b = await createGym('CrossFit');
    expect(a.orden).toBe(0);
    expect(b.orden).toBe(1);
    expect((await listGyms()).map((g) => g.id)).toEqual([a.id, b.id]);
  });

  it('renombra', async () => {
    const g = await createGym('A');
    await renameGym(g.id, 'B');
    expect((await db.gyms.get(g.id))?.nombre).toBe('B');
  });

  it('archivar oculta de listGyms pero conserva el registro', async () => {
    const g = await createGym('A');
    await archiveGym(g.id, true);
    expect(await listGyms()).toHaveLength(0);
    expect((await db.gyms.get(g.id))?.archivada).toBe(true);
  });

  it('soft-delete no borra las sesiones que lo referencian', async () => {
    const g = await createGym('A');
    await db.workoutSessions.put({
      id: 's1', userId: null, gymId: g.id, fecha: Date.now(),
      updatedAt: Date.now(), deletedAt: null,
    });
    await softDeleteGym(g.id);
    expect(await listGyms()).toHaveLength(0);
    expect((await db.gyms.get(g.id))?.deletedAt).not.toBeNull();
    expect((await db.workoutSessions.get('s1'))?.deletedAt).toBeNull();
    expect((await db.workoutSessions.get('s1'))?.gymId).toBe(g.id);
  });

  it('reordena por la lista de ids dada', async () => {
    const a = await createGym('A');
    const b = await createGym('B');
    await reorderGyms([b.id, a.id]);
    expect((await listGyms()).map((g) => g.id)).toEqual([b.id, a.id]);
  });

  it('getGymsMap incluye archivados para resolver nombres', async () => {
    const g = await createGym('A');
    await archiveGym(g.id, true);
    const map = await getGymsMap();
    expect(map.get(g.id)?.nombre).toBe('A');
  });
});
