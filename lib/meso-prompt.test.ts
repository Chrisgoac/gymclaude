import { describe, it, expect } from 'vitest';
import { promptMesociclo, MESO_SCHEMA } from '@/lib/meso-prompt';
import type { CoachSnapshot } from '@/lib/coach-snapshot';

const snap: CoachSnapshot = {
  estancados: [{ ejercicio: 'Press banca', sesionesSinMejora: 4 }],
  semana: { sesiones: 2, objetivo: 4, volumen: 1000, deltaPct: null, prs: [] },
  grupos: [{ grupo: 'Pecho', volumenSemana: 500, diasSinEntrenar: 3, objetivo: null }],
  cuerpo: { peso: null, medidas: [] },
};

describe('promptMesociclo', () => {
  it('incluye objetivo, días, semanas y referencia catálogo + estancados', () => {
    const p = promptMesociclo(
      { objetivo: 'hipertrofia', diasPorSemana: 4, semanas: 6, minutosPorSesion: 60 },
      snap,
      [{ nombre: 'Sentadilla', grupo: 'cuadriceps', equipamiento: 'barra' }],
    );
    expect(p).toContain('hipertrofia');
    expect(p).toContain('4'); // días
    expect(p).toContain('6'); // semanas
    expect(p).toContain('Sentadilla');
    expect(p.toLowerCase()).toContain('press banca'); // del snapshot (estancado)
  });
});

describe('MESO_SCHEMA', () => {
  it('exige dias y progresion', () => {
    expect(MESO_SCHEMA.required).toContain('dias');
    expect(MESO_SCHEMA.required).toContain('progresion');
  });
});
