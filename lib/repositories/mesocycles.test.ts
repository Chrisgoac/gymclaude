import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import {
  createMesocycle, getMesocycle, listMesocycles, deleteMesocycle, semanaActual,
} from '@/lib/repositories/mesocycles';
import type { Mesocycle } from '@/lib/db/types';

const DIA = 86400000;
const base = {
  nombre: 'Hipertrofia', objetivo: 'hipertrofia', semanas: 6, diasPorSemana: 4,
  notas: null as string | null, progresion: [{ semana: 1, descarga: false, ajuste: '3x10' }], fechaInicio: 1000,
};

beforeEach(async () => { await db.mesocycles.clear(); });

describe('mesocycles repo', () => {
  it('createMesocycle + get + list', async () => {
    const m = await createMesocycle(base);
    expect(m.id).toBeTruthy();
    expect((await getMesocycle(m.id))?.nombre).toBe('Hipertrofia');
    expect(await listMesocycles()).toHaveLength(1);
  });
  it('deleteMesocycle hace tombstone (list lo excluye)', async () => {
    const m = await createMesocycle(base);
    await deleteMesocycle(m.id);
    expect(await listMesocycles()).toHaveLength(0);
  });
});

describe('semanaActual', () => {
  const meso = { ...base, semanas: 6, fechaInicio: 0 } as unknown as Mesocycle;
  it('antes/al inicio → 1', () => {
    expect(semanaActual(meso, 0)).toBe(1);
    expect(semanaActual(meso, 3 * DIA)).toBe(1);
  });
  it('semana intermedia', () => {
    expect(semanaActual(meso, 8 * DIA)).toBe(2); // día 8 → semana 2
  });
  it('pasado el final → acota a semanas', () => {
    expect(semanaActual(meso, 100 * DIA)).toBe(6);
  });
});
