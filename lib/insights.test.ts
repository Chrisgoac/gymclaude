import { describe, it, expect } from 'vitest';
import { detectarEstancamiento } from '@/lib/insights';

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
});
