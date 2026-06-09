import { describe, it, expect } from 'vitest';
import { construirSnapshot, type SnapshotInput } from '@/lib/coach-snapshot';

const DIA = 86400000;
const AHORA = 100 * DIA;
const GRUPOS = ['pecho','espalda','hombros','biceps','triceps','cuadriceps','femoral','gluteo','gemelo','abdomen','antebrazo','otro'];

function baseInput(): SnapshotInput {
  return {
    estancados: [],
    semana: { sesiones: 2, volumenSemana: 1234.6, volumenSemanaPrevia: 1000, deltaPct: 23 },
    objetivoSemanal: 3,
    prs: [],
    volumenSemanaPorGrupo: Object.fromEntries(GRUPOS.map((g) => [g, 0])) as SnapshotInput['volumenSemanaPorGrupo'],
    lastTrained: Object.fromEntries(GRUPOS.map((g) => [g, null])) as SnapshotInput['lastTrained'],
    objetivosVolumen: {},
    ahora: AHORA,
  };
}

describe('construirSnapshot', () => {
  it('semana: redondea volumen y copia objetivo/deltaPct/PRs', () => {
    const inp = baseInput();
    inp.prs = [{ exerciseId: 'e1', nombre: 'Press', tipo: 'peso' }];
    const s = construirSnapshot(inp);
    expect(s.semana).toEqual({ sesiones: 2, objetivo: 3, volumen: 1235, deltaPct: 23, prs: [{ ejercicio: 'Press', tipo: 'peso' }] });
  });

  it('estancados: top 5, mapea nombre + sesionesSinMejora', () => {
    const inp = baseInput();
    inp.estancados = Array.from({ length: 7 }, (_, i) => ({ exerciseId: `e${i}`, nombre: `Ej${i}`, sesionesSinMejora: i + 3, ultimaMejoraFecha: 0 }));
    const s = construirSnapshot(inp);
    expect(s.estancados).toHaveLength(5);
    expect(s.estancados[0]).toEqual({ ejercicio: 'Ej0', sesionesSinMejora: 3 });
  });

  it('grupos: incluye con volumen/objetivo/entrenado, ordena por volumen desc, días sin entrenar', () => {
    const inp = baseInput();
    inp.volumenSemanaPorGrupo.pecho = 800;
    inp.volumenSemanaPorGrupo.espalda = 500;
    inp.lastTrained.pecho = AHORA - 2 * DIA;
    inp.lastTrained.espalda = AHORA - 12 * DIA;
    inp.objetivosVolumen.gluteo = 600;
    const s = construirSnapshot(inp);
    const grupos = s.grupos.map((g) => g.grupo);
    expect(grupos.slice(0, 2)).toEqual(['Pecho', 'Espalda']);
    expect(grupos).toContain('Glúteo');
    expect(s.grupos.find((g) => g.grupo === 'Pecho')).toEqual({ grupo: 'Pecho', volumenSemana: 800, diasSinEntrenar: 2, objetivo: null });
    const gluteo = s.grupos.find((g) => g.grupo === 'Glúteo')!;
    expect(gluteo.objetivo).toBe(600);
    expect(gluteo.diasSinEntrenar).toBeNull();
  });

  it('grupos: excluye los sin volumen/objetivo/entrenamiento; lista vacía', () => {
    expect(construirSnapshot(baseInput()).grupos).toHaveLength(0);
  });
});
