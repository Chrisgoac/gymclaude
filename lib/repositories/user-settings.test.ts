import { describe, it, expect, beforeEach } from 'vitest';
import 'fake-indexeddb/auto';
import { db } from '@/lib/db/database';
import { getSetting, setSetting, deleteSetting, migrarAjustesLocales } from '@/lib/repositories/user-settings';

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

describe('migrarAjustesLocales', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('siembra modo e incrementos desde localStorage', async () => {
    localStorage.setItem('gymlog.modoProgresion', 'doble');
    localStorage.setItem('gymlog.incrementos', JSON.stringify({ maquina: 7.5 }));
    await migrarAjustesLocales();
    expect(await getSetting<string>('modoProgresion')).toBe('doble');
    expect(await getSetting<Record<string, number>>('incrementos')).toEqual({ maquina: 7.5 });
  });

  it('es idempotente: no pisa un valor ya existente', async () => {
    await setSetting('modoProgresion', 'off');
    localStorage.setItem('gymlog.modoProgresion', 'doble');
    await migrarAjustesLocales();
    expect(await getSetting<string>('modoProgresion')).toBe('off');
  });

  it('sin nada en localStorage no crea filas', async () => {
    await migrarAjustesLocales();
    expect(await db.userSettings.count()).toBe(0);
  });

  it('no re-siembra si la clave está borrada (tombstone)', async () => {
    await setSetting('modoProgresion', 'off');
    await deleteSetting('modoProgresion');
    localStorage.setItem('gymlog.modoProgresion', 'doble');
    await migrarAjustesLocales();
    expect(await getSetting<string>('modoProgresion')).toBeUndefined();
  });
});
