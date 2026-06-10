import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { reconciliarLogros } from '@/lib/reconciliar-logros';
import { listAchievements } from '@/lib/repositories/achievements';

async function sembrarSesiones(n: number) {
  for (let i = 0; i < n; i++) {
    await db.workoutSessions.put({ id: `s${i}`, userId: null, fecha: 1000 + i, updatedAt: 1, deletedAt: null });
  }
}

beforeEach(async () => {
  await Promise.all([db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(), db.mesocycles.clear(), db.achievements.clear()]);
});

describe('reconciliarLogros', () => {
  it('desbloquea los hitos cumplidos y es idempotente', async () => {
    await sembrarSesiones(10); // cumple sesiones-10
    const nuevas = await reconciliarLogros(3, 99999999);
    expect(nuevas).toContain('sesiones-10');
    expect((await listAchievements()).map((a) => a.clave)).toContain('sesiones-10');

    // segunda llamada: no añade nada nuevo
    const otra = await reconciliarLogros(3, 99999999);
    expect(otra).toEqual([]);
    expect((await listAchievements()).filter((a) => a.clave === 'sesiones-10')).toHaveLength(1);
  });

  it('sin datos no desbloquea nada', async () => {
    expect(await reconciliarLogros(3, 99999999)).toEqual([]);
    expect(await listAchievements()).toHaveLength(0);
  });
});
