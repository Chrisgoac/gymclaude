import { describe, it, expect, beforeEach } from 'vitest';
import 'fake-indexeddb/auto';
import { db } from '@/lib/db/database';
import { getSetting, setSetting, deleteSetting } from '@/lib/repositories/user-settings';

beforeEach(async () => {
  await db.userSettings.clear();
});

describe('user-settings repo', () => {
  it('round-trip de un número', async () => {
    await setSetting('objetivoSemanal', 4);
    expect(await getSetting<number>('objetivoSemanal')).toBe(4);
  });
  it('round-trip de un objeto', async () => {
    await setSetting('incrementos', { barra: 2.5, maquina: 5 });
    expect(await getSetting<Record<string, number>>('incrementos')).toEqual({ barra: 2.5, maquina: 5 });
  });
  it('clave inexistente → undefined', async () => {
    expect(await getSetting('nada')).toBeUndefined();
  });
  it('setSetting reescribe y sube updatedAt', async () => {
    await setSetting('x', 1);
    const a = await db.userSettings.get('x');
    await setSetting('x', 2);
    const b = await db.userSettings.get('x');
    expect(await getSetting<number>('x')).toBe(2);
    expect(b!.updatedAt).toBeGreaterThanOrEqual(a!.updatedAt);
  });
  it('deleteSetting (tombstone) → getSetting undefined', async () => {
    await setSetting('x', 1);
    await deleteSetting('x');
    expect(await getSetting('x')).toBeUndefined();
    const row = await db.userSettings.get('x');
    expect(row!.deletedAt).not.toBeNull();
  });
  it('setSetting tras deleteSetting reactiva la clave', async () => {
    await setSetting('x', 1);
    await deleteSetting('x');
    await setSetting('x', 2);
    expect(await getSetting<number>('x')).toBe(2);
  });
  it('valor corrupto → undefined', async () => {
    await db.userSettings.put({ id: 'roto', userId: null, valor: '{no json', updatedAt: 1, deletedAt: null });
    expect(await getSetting('roto')).toBeUndefined();
  });
});
