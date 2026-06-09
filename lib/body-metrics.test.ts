import { describe, it, expect } from 'vitest';
import {
  METRICAS_PREDEF,
  ORDEN_PREDEF,
  slugify,
  resolverMetrica,
  type MetricaPersonalizada,
} from '@/lib/body-metrics';
import { getSetting } from '@/lib/repositories/user-settings';
import { db } from '@/lib/db/database';
import { CLAVE_PERSONALIZADAS, addMetricaPersonalizada, listMetricasPersonalizadas } from '@/lib/body-metrics';

describe('METRICAS_PREDEF', () => {
  it('peso está en kg y las medidas en cm', () => {
    expect(METRICAS_PREDEF.peso).toEqual({ label: 'Peso', unidad: 'kg' });
    expect(METRICAS_PREDEF.cintura.unidad).toBe('cm');
  });
  it('ORDEN_PREDEF empieza por peso y cubre todas las claves', () => {
    expect(ORDEN_PREDEF[0]).toBe('peso');
    expect([...ORDEN_PREDEF].sort()).toEqual(Object.keys(METRICAS_PREDEF).sort());
  });
});

describe('slugify', () => {
  it('normaliza acentos, espacios y mayúsculas', () => {
    expect(slugify('Brazo Derecho')).toBe('brazo-derecho');
    expect(slugify('  Glúteo  ')).toBe('gluteo');
  });
  it('colapsa caracteres no alfanuméricos', () => {
    expect(slugify('% grasa!!')).toBe('grasa');
  });
});

describe('resolverMetrica', () => {
  const pers: MetricaPersonalizada[] = [{ clave: 'grasa', label: '% Grasa', unidad: '%' }];
  it('resuelve una predefinida', () => {
    expect(resolverMetrica('cintura', pers)).toEqual({ label: 'Cintura', unidad: 'cm' });
  });
  it('resuelve una personalizada', () => {
    expect(resolverMetrica('grasa', pers)).toEqual({ label: '% Grasa', unidad: '%' });
  });
  it('cae al fallback para una desconocida', () => {
    expect(resolverMetrica('xyz', pers)).toEqual({ label: 'xyz', unidad: '' });
  });
  it('predefinida tiene prioridad sobre personalizada con la misma clave', () => {
    expect(resolverMetrica('peso', [{ clave: 'peso', label: 'X', unidad: 'lb' }])).toEqual({
      label: 'Peso', unidad: 'kg',
    });
  });
});

describe('personalizadas (C0)', () => {
  it('añade una personalizada, la persiste y la devuelve', async () => {
    await db.userSettings.clear();
    const m = await addMetricaPersonalizada('% Grasa', '%');
    expect(m).toEqual({ clave: 'grasa', label: '% Grasa', unidad: '%' });
    expect(await getSetting(CLAVE_PERSONALIZADAS)).toEqual([m]);
    expect(await listMetricasPersonalizadas()).toEqual([m]);
  });
  it('es idempotente por clave (no duplica)', async () => {
    await db.userSettings.clear();
    await addMetricaPersonalizada('Glúteo', 'cm');
    const segunda = await addMetricaPersonalizada('glúteo', 'cm');
    expect(segunda.clave).toBe('gluteo');
    expect(await listMetricasPersonalizadas()).toHaveLength(1);
  });
  it('rechaza una clave que choca con una predefinida', async () => {
    await db.userSettings.clear();
    await expect(addMetricaPersonalizada('Peso', 'lb')).rejects.toThrow();
  });
});
