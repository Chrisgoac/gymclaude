import { describe, it, expect } from 'vitest';
import { resumenSerie } from '@/lib/body-stats';
import type { BodyMetric } from '@/lib/db/types';

const bm = (valor: number, fecha: number): BodyMetric => ({
  id: `${fecha}`, userId: null, tipo: 'peso', valor, fecha, updatedAt: fecha, deletedAt: null,
});

describe('resumenSerie', () => {
  it('sin datos → todo vacío', () => {
    expect(resumenSerie([])).toEqual({ actual: null, primero: null, delta: null, puntos: [] });
  });
  it('una sola entrada → delta null', () => {
    const r = resumenSerie([bm(80, 1000)]);
    expect(r.actual).toBe(80);
    expect(r.primero).toBe(80);
    expect(r.delta).toBeNull();
    expect(r.puntos).toHaveLength(1);
  });
  it('varias entradas → actual=última, delta vs primera', () => {
    const r = resumenSerie([bm(80, 1000), bm(78.5, 2000), bm(77, 3000)]);
    expect(r.actual).toBe(77);
    expect(r.primero).toBe(80);
    expect(r.delta).toBe(-3);
    expect(r.puntos.map((p) => p.valor)).toEqual([80, 78.5, 77]);
  });
});

// Shared with the Android implementation (android/core/domain DomainParityTest).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

type BodyCase = { name: string; zone: string; metrics: { valor: number; fecha: number }[]; expected: unknown };
const bodyFixture: { cases: BodyCase[] } = JSON.parse(
  readFileSync(join(process.cwd(), 'contracts/domain/body-stats-cases.json'), 'utf8'),
);

describe('resumenSerie (shared fixture contracts/domain/body-stats-cases.json)', () => {
  it.each(bodyFixture.cases)('$name', ({ zone, metrics, expected }) => {
    const previous = process.env.TZ;
    process.env.TZ = zone;
    try {
      expect(resumenSerie(metrics.map(({ valor, fecha }) => bm(valor, fecha)))).toEqual(expected);
    } finally {
      if (previous === undefined) delete process.env.TZ; else process.env.TZ = previous;
    }
  });
});
