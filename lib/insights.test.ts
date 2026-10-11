import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { detectarEstancamiento, filasEquilibrio } from '@/lib/insights';
import { weeklyVolumeDeltas } from '@/lib/repositories/stats';
import { construirSnapshot } from '@/lib/coach-snapshot';

function pts(rms: number[]): { fecha: number; maxPeso: number; mejor1RM: number; volumen: number }[] {
  return rms.map((rm, i) => ({ fecha: 1000 + i, maxPeso: 0, mejor1RM: rm, volumen: 0 }));
}

describe('detectarEstancamiento', () => {
  it('datos insuficientes (< n+1 sesiones) → no estancado', () => {
    expect(detectarEstancamiento(pts([100, 100, 100]), 3)).toEqual({
      estancado: false, sesionesSinMejora: 0, ultimaMejoraFecha: null,
    });
  });

  it('estancado: subió a 105 y luego se quedó plano', () => {
    const r = detectarEstancamiento(pts([105, 100, 100, 100]), 3);
    expect(r.estancado).toBe(true);
    expect(r.sesionesSinMejora).toBe(3);
    expect(r.ultimaMejoraFecha).toBe(1000);
  });

  it('empate exacto cuenta como estancado', () => {
    expect(detectarEstancamiento(pts([100, 100, 100, 100]), 3).estancado).toBe(true);
  });

  it('mejorando: nuevo máximo en la última sesión → no estancado', () => {
    const r = detectarEstancamiento(pts([100, 100, 100, 105]), 3);
    expect(r.estancado).toBe(false);
    expect(r.sesionesSinMejora).toBe(0);
    expect(r.ultimaMejoraFecha).toBe(1003);
  });

  it('mejora dentro de la ventana reciente → no estancado', () => {
    const r = detectarEstancamiento(pts([100, 100, 110, 108]), 3);
    expect(r.estancado).toBe(false);
  });

  it('respeta un n distinto del default (n=2)', () => {
    // 3 sesiones, n=2: reciente=max(últimas 2)=[100,100]=100; previo=max(primera)=105 → estancado
    expect(detectarEstancamiento(pts([105, 100, 100]), 2).estancado).toBe(true);
    // con un nuevo máximo en la última y n=2 → no estancado
    expect(detectarEstancamiento(pts([100, 100, 106]), 2).estancado).toBe(false);
  });
});

// The dataset outputs are checked by scripts/export-domain-fixtures.test.ts (it re-runs the stats on Dexie);
// here the pure functions run the same file directly.
const insightFixture = JSON.parse(readFileSync(join(process.cwd(), 'contracts/domain/insight-cases.json'), 'utf8'));

describe('shared fixture contracts/domain/insight-cases.json', () => {
  it('detectarEstancamiento', () => {
    for (const c of insightFixture.stalls) {
      const points = c.mejor1RM.map((rm: number, i: number) => ({ fecha: 1000 + i, maxPeso: 0, mejor1RM: rm, volumen: 0 }));
      expect(detectarEstancamiento(points, c.n)).toEqual(c.expected);
    }
  });
  it('weeklyVolumeDeltas', () => {
    for (const c of insightFixture.weeklyVolumeDeltas) {
      const points = c.volumes.map((volumen: number, i: number) => ({ semanaInicioTs: i, volumen }));
      // Math.round can yield -0, which JSON stores as 0.
      expect(weeklyVolumeDeltas(points).map((p) => (p.deltaPct === 0 ? 0 : p.deltaPct))).toEqual(c.expected);
    }
  });
  it('filasEquilibrio from each dataset’s own volume/last-trained outputs', () => {
    for (const d of insightFixture.datasets) {
      for (const g of d.expected.byGym) {
        for (const p of g.periods) {
          expect(filasEquilibrio({
            data: p.volumeByMuscle, lastTrained: g.lastTrainedByMuscle, ahora: d.now,
            objetivos: d.objetivosVolumen, volumenSemana: g.volumenSemanaByMuscle,
          })).toEqual(p.balance);
        }
      }
    }
  });
  it('construirSnapshot', () => {
    for (const c of insightFixture.coachSnapshot) expect(construirSnapshot(c.input)).toEqual(c.expected);
  });
});
