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
