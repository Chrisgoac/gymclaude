import { describe, it, expect } from 'vitest';
import { LOGROS_DEF, evaluarLogros, calcularRacha, type LogroMetricas } from '@/lib/logros';

const DIA = 86400000;
const SEMANA = 7 * DIA;
const base: LogroMetricas = { sesionesTotales: 0, volumenTotal: 0, prsTotales: 0, mejorRacha: 0, mesociclosCompletados: 0 };

describe('evaluarLogros', () => {
  it('cumple los hitos cuyos umbrales se alcanzan', () => {
    const claves = evaluarLogros({ ...base, sesionesTotales: 55, volumenTotal: 120000, mejorRacha: 4, mesociclosCompletados: 1 });
    expect(claves).toContain('sesiones-10');
    expect(claves).toContain('sesiones-50');
    expect(claves).not.toContain('sesiones-100');
    expect(claves).toContain('volumen-100k');
    expect(claves).toContain('racha-4');
    expect(claves).not.toContain('racha-8');
    expect(claves).toContain('mesociclo-1');
  });
  it('sin datos no cumple ninguno', () => {
    expect(evaluarLogros(base)).toEqual([]);
  });
  it('todas las claves del catálogo son únicas', () => {
    const claves = LOGROS_DEF.map((d) => d.clave);
    expect(new Set(claves).size).toBe(claves.length);
  });
});

describe('calcularRacha', () => {
  const W = (n: number) => n * SEMANA; // inicio de la semana n
  it('racha actual cuenta semanas consecutivas cumpliendo, hacia atrás', () => {
    const semanas = [
      { inicioTs: W(1), sesiones: 3 },
      { inicioTs: W(2), sesiones: 4 },
      { inicioTs: W(3), sesiones: 3 }, // actual
    ];
    expect(calcularRacha(semanas, 3, W(3))).toEqual({ actual: 3, mejor: 3 });
  });
  it('la semana actual en curso por debajo del objetivo no rompe la racha', () => {
    const semanas = [
      { inicioTs: W(1), sesiones: 3 },
      { inicioTs: W(2), sesiones: 3 },
      { inicioTs: W(3), sesiones: 1 }, // actual, aún por debajo
    ];
    expect(calcularRacha(semanas, 3, W(3))).toEqual({ actual: 2, mejor: 2 });
  });
  it('un hueco rompe la racha; mejor = la racha histórica más larga', () => {
    const semanas = [
      { inicioTs: W(1), sesiones: 3 },
      { inicioTs: W(2), sesiones: 3 },
      { inicioTs: W(3), sesiones: 1 }, // no cumple (hueco)
      { inicioTs: W(4), sesiones: 3 }, // actual
    ];
    expect(calcularRacha(semanas, 3, W(4))).toEqual({ actual: 1, mejor: 2 });
  });
  it('sin semanas → 0/0', () => {
    expect(calcularRacha([], 3, W(1))).toEqual({ actual: 0, mejor: 0 });
  });
});
