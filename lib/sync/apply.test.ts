import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { pickWinner, applyIncoming } from '@/lib/sync/apply';
import type { SyncMeta } from '@/lib/db/types';

beforeEach(async () => {
  await db.routines.clear();
});

describe('pickWinner (LWW)', () => {
  it('gana el de mayor updatedAt; sin local gana el entrante', () => {
    const a: SyncMeta = { id: '1', updatedAt: 10, deletedAt: null };
    const b: SyncMeta = { id: '1', updatedAt: 20, deletedAt: null };
    expect(pickWinner(a, b)).toBe(b);
    expect(pickWinner(b, a)).toBe(b);
    expect(pickWinner(undefined, a)).toBe(a);
  });
});

describe('applyIncoming', () => {
  it('inserta entrantes nuevos y respeta el más reciente', async () => {
    await db.routines.put({ id: 'r1', userId: null, nombre: 'Local', orden: 0, archivada: false, updatedAt: 100, deletedAt: null });
    await applyIncoming([
      { table: 'routines', records: [
        { id: 'r1', userId: null, nombre: 'RemotoNuevo', archivada: false, updatedAt: 200, deletedAt: null } as unknown as SyncMeta,
        { id: 'r2', userId: null, nombre: 'OtroRemoto', archivada: false, updatedAt: 50, deletedAt: null } as unknown as SyncMeta,
      ] },
    ]);
    expect((await db.routines.get('r1'))?.nombre).toBe('RemotoNuevo');
    expect(await db.routines.get('r2')).toBeDefined();
  });

  it('no pisa un local más reciente que el entrante', async () => {
    await db.routines.put({ id: 'r1', userId: null, nombre: 'LocalNuevo', orden: 0, archivada: false, updatedAt: 300, deletedAt: null });
    await applyIncoming([
      { table: 'routines', records: [
        { id: 'r1', userId: null, nombre: 'RemotoViejo', archivada: false, updatedAt: 100, deletedAt: null } as unknown as SyncMeta,
      ] },
    ]);
    expect((await db.routines.get('r1'))?.nombre).toBe('LocalNuevo');
  });

  it('aplica tombstones (borrados) entrantes', async () => {
    await db.routines.put({ id: 'r1', userId: null, nombre: 'X', orden: 0, archivada: false, updatedAt: 100, deletedAt: null });
    await applyIncoming([
      { table: 'routines', records: [
        { id: 'r1', userId: null, nombre: 'X', archivada: false, updatedAt: 200, deletedAt: 200 } as unknown as SyncMeta,
      ] },
    ]);
    expect((await db.routines.get('r1'))?.deletedAt).toBe(200);
  });
});

it('userSettings converge por id-clave (LWW)', async () => {
  await db.userSettings.clear();
  await db.userSettings.put({ id: 'modoProgresion', userId: 'u1', valor: '"off"', updatedAt: 100, deletedAt: null });
  await applyIncoming([{ table: 'userSettings', records: [
    { id: 'modoProgresion', userId: 'u1', valor: '"doble"', updatedAt: 200, deletedAt: null } as unknown as SyncMeta,
  ] }]);
  const row = await db.userSettings.get('modoProgresion');
  expect(row!.valor).toBe('"doble"'); // gana el updatedAt mayor
  expect(await db.userSettings.count()).toBe(1); // mismo id → no duplica
});

it('coachMessages se aplican por id (LWW)', async () => {
  await db.coachMessages.clear();
  await applyIncoming([{ table: 'coachMessages', records: [
    { id: 'm1', userId: 'u1', rol: 'user', contenido: 'hola', createdAt: 100, updatedAt: 100, deletedAt: null },
  ] as unknown as import('@/lib/db/types').SyncMeta[] }]);
  const m = await db.coachMessages.get('m1');
  expect(m?.contenido).toBe('hola');
  expect(await db.coachMessages.count()).toBe(1);
});

it('bodyMetrics se aplican por id (LWW)', async () => {
  await db.bodyMetrics.clear();
  await applyIncoming([{ table: 'bodyMetrics', records: [
    { id: 'bm1', userId: 'u1', tipo: 'peso', valor: 80, fecha: 100, updatedAt: 100, deletedAt: null },
  ] as unknown as import('@/lib/db/types').SyncMeta[] }]);
  const m = await db.bodyMetrics.get('bm1');
  expect(m?.valor).toBe(80);
  expect(await db.bodyMetrics.count()).toBe(1);
});

it('progressPhotos se aplican por id (LWW)', async () => {
  await db.progressPhotos.clear();
  await applyIncoming([{ table: 'progressPhotos', records: [
    { id: 'pp1', userId: 'u1', url: 'u', key: 'k', fecha: 100, angulo: 'frente', nota: null, updatedAt: 100, deletedAt: null },
  ] as unknown as import('@/lib/db/types').SyncMeta[] }]);
  const p = await db.progressPhotos.get('pp1');
  expect(p?.key).toBe('k');
  expect(await db.progressPhotos.count()).toBe(1);
});

it('mesocycles se aplican por id (LWW)', async () => {
  await db.mesocycles.clear();
  await applyIncoming([{ table: 'mesocycles', records: [
    { id: 'me1', userId: 'u1', nombre: 'H', objetivo: 'hipertrofia', semanas: 6, diasPorSemana: 4, notas: null, progresion: [], fechaInicio: 1, updatedAt: 100, deletedAt: null },
  ] as unknown as import('@/lib/db/types').SyncMeta[] }]);
  expect((await db.mesocycles.get('me1'))?.nombre).toBe('H');
});
