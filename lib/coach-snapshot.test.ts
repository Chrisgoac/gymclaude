import { describe, it, expect } from 'vitest';
import { construirSnapshot, type SnapshotInput } from '@/lib/coach-snapshot';
import type { MuscleGroup } from '@/lib/db/types';

const DIA = 86400000;
const AHORA = 100 * DIA;
const GRUPOS: MuscleGroup[] = ['pecho','espalda','hombros','biceps','triceps','cuadriceps','femoral','gluteo','gemelo','abdomen','antebrazo','otro'];

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
    cuerpo: [],
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

  it('grupos: limita a 8 grupos', () => {
    const inp = baseInput();
    for (const g of GRUPOS) inp.volumenSemanaPorGrupo[g] = 100; // los 12 con volumen
    expect(construirSnapshot(inp).grupos).toHaveLength(8);
  });

  it('cuerpo: sin datos → peso null y medidas vacías', () => {
    const s = construirSnapshot(baseInput());
    expect(s.cuerpo).toEqual({ peso: null, medidas: [] });
  });

  it('cuerpo: peso con actual + delta4sem (vs más antiguo en ventana 4 sem)', () => {
    const inp = baseInput();
    inp.cuerpo = [
      {
        tipo: 'peso',
        label: 'Peso',
        entradas: [
          { valor: 82, fecha: AHORA - 40 * DIA }, // fuera de ventana → ignorada para delta
          { valor: 80, fecha: AHORA - 20 * DIA }, // referencia (más antigua en ventana)
          { valor: 78, fecha: AHORA - 2 * DIA },  // actual
        ],
      },
    ];
    const s = construirSnapshot(inp);
    expect(s.cuerpo.peso).toEqual({ actual: 78, delta4sem: -2 });
    expect(s.cuerpo.medidas).toHaveLength(0);
  });

  it('cuerpo: una sola entrada en ventana → delta4sem null', () => {
    const inp = baseInput();
    inp.cuerpo = [{ tipo: 'peso', label: 'Peso', entradas: [{ valor: 80, fecha: AHORA - 1 * DIA }] }];
    expect(construirSnapshot(inp).cuerpo.peso).toEqual({ actual: 80, delta4sem: null });
  });

  it('cuerpo: medidas clave ordenadas por recencia, top 6', () => {
    const inp = baseInput();
    inp.cuerpo = Array.from({ length: 8 }, (_, i) => ({
      tipo: `m${i}`,
      label: `M${i}`,
      entradas: [{ valor: 30 + i, fecha: AHORA - i * DIA }], // m0 la más reciente
    }));
    const s = construirSnapshot(inp);
    expect(s.cuerpo.medidas).toHaveLength(6);
    expect(s.cuerpo.medidas[0].metrica).toBe('M0');
    expect(s.cuerpo.medidas[0]).toEqual({ metrica: 'M0', actual: 30, delta4sem: null });
  });
});

import { recogerSnapshot } from '@/lib/coach-snapshot';
import { db } from '@/lib/db/database';
import { setSetting } from '@/lib/repositories/user-settings';

it('recogerSnapshot reúne las señales reales en el snapshot', async () => {
  await Promise.all([
    db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(),
    db.exercises.clear(), db.userSettings.clear(),
  ]);
  const NOW = new Date('2026-06-10T12:00:00').getTime(); // miércoles
  await db.exercises.put({ id: 'snap-ex', userId: null, nombre: 'Press banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null });
  await db.workoutSessions.put({ id: 'snap-s', userId: null, gymId: 'g1', fecha: NOW, updatedAt: 1, deletedAt: null });
  await db.loggedExercises.put({ id: 'snap-le', sessionId: 'snap-s', exerciseId: 'snap-ex', orden: 0, updatedAt: 1, deletedAt: null });
  await db.loggedSets.put({ id: 'snap-set', loggedExerciseId: 'snap-le', orden: 0, peso: 50, reps: 10, updatedAt: 1, deletedAt: null });
  await setSetting('objetivoSemanal', 4);

  const snap = await recogerSnapshot('g1', NOW);
  expect(snap.semana.sesiones).toBe(1);
  expect(snap.semana.objetivo).toBe(4);
  const pecho = snap.grupos.find((g) => g.grupo === 'Pecho');
  expect(pecho?.volumenSemana).toBe(500);
  expect(pecho?.diasSinEntrenar).toBe(0);
});

it('recogerSnapshot incluye peso y medidas corporales', async () => {
  await Promise.all([
    db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(),
    db.exercises.clear(), db.userSettings.clear(), db.bodyMetrics.clear(),
  ]);
  const NOW = new Date('2026-06-10T12:00:00').getTime();
  const DIAMS = 86400000;
  await db.bodyMetrics.bulkPut([
    { id: 'p1', userId: null, tipo: 'peso', valor: 80, fecha: NOW - 20 * DIAMS, updatedAt: 1, deletedAt: null },
    { id: 'p2', userId: null, tipo: 'peso', valor: 78, fecha: NOW - 1 * DIAMS, updatedAt: 1, deletedAt: null },
    { id: 'c1', userId: null, tipo: 'cintura', valor: 85, fecha: NOW - 2 * DIAMS, updatedAt: 1, deletedAt: null },
  ]);
  const snap = await recogerSnapshot('g1', NOW);
  expect(snap.cuerpo.peso).toEqual({ actual: 78, delta4sem: -2 });
  expect(snap.cuerpo.medidas.map((m) => m.metrica)).toContain('Cintura');
});
