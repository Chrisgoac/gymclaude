import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { listAchievements, unlockAchievement, getAchievementMap } from '@/lib/repositories/achievements';

beforeEach(async () => { await db.achievements.clear(); });

describe('achievements repo', () => {
  it('unlockAchievement crea un logro con fecha', async () => {
    await unlockAchievement('sesiones-10');
    const todos = await listAchievements();
    expect(todos).toHaveLength(1);
    expect(todos[0].clave).toBe('sesiones-10');
    expect(todos[0].fechaDesbloqueo).toBeGreaterThan(0);
    expect(todos[0].deletedAt).toBeNull();
  });
  it('unlockAchievement es idempotente por clave', async () => {
    await unlockAchievement('racha-4');
    await unlockAchievement('racha-4');
    expect(await listAchievements()).toHaveLength(1);
  });
  it('getAchievementMap indexa por clave', async () => {
    await unlockAchievement('volumen-100k');
    const map = await getAchievementMap();
    expect(map.get('volumen-100k')?.clave).toBe('volumen-100k');
    expect(map.has('inexistente')).toBe(false);
  });
});
