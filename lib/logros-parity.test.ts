import { describe, it, expect, beforeEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { db } from '@/lib/db/database';
import { LOGROS_DEF, evaluarLogros, calcularRacha, type LogroMetricas } from '@/lib/logros';
import { reconciliarLogros } from '@/lib/reconciliar-logros';
import { getLogroMetricas, listPRs } from '@/lib/repositories/stats';

// Shared with the Android implementation (android/core/domain AchievementParityTest, core:data OfflineAchievementRepositoryTest).
const fixture = JSON.parse(readFileSync(join(process.cwd(), 'contracts/domain/achievement-cases.json'), 'utf8'));

async function inZone<T>(zone: string, fn: () => Promise<T>): Promise<T> {
  const previous = process.env.TZ;
  process.env.TZ = zone;
  try {
    return await fn();
  } finally {
    if (previous === undefined) delete process.env.TZ;
    else process.env.TZ = previous;
  }
}

describe('shared fixture contracts/domain/achievement-cases.json', () => {
  it('catalog matches LOGROS_DEF', () => {
    expect(fixture.catalog).toEqual(LOGROS_DEF.map(({ clave, titulo, descripcion }) => ({ clave, titulo, descripcion })));
  });

  it.each(fixture.evaluate as { metrics: LogroMetricas; expected: string[] }[])('evaluarLogros %#', ({ metrics, expected }) => {
    expect(evaluarLogros(metrics)).toEqual(expected);
  });

  it.each(fixture.streak as { name: string; weeks: { inicioTs: number; sesiones: number }[]; target: number; currentWeekStart: number; expected: unknown }[])(
    'calcularRacha $name',
    ({ weeks, target, currentWeekStart, expected }) => expect(calcularRacha(weeks, target, currentWeekStart)).toEqual(expected),
  );

  describe('scenarios', () => {
    beforeEach(async () => {
      await Promise.all([db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(), db.mesocycles.clear(), db.achievements.clear(), db.exercises.clear()]);
    });

    for (const scenario of fixture.scenarios) {
      for (const step of scenario.steps) {
        it(`${scenario.name}: ${step.label}`, async () => {
          await db.exercises.bulkPut([
            { id: 'ex-press', userId: null, nombre: 'Press de banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null },
            { id: 'ex-squat', userId: null, nombre: 'Sentadilla', grupoMuscular: 'cuadriceps', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null },
          ] as never);
          await db.workoutSessions.bulkPut(step.sessions.map(({ duracionSegundos, ...s }: { duracionSegundos: number | null }) => ({
            ...s, userId: null, routineId: null, updatedAt: 1, ...(duracionSegundos == null ? {} : { duracionSegundos }),
          })));
          await db.loggedExercises.bulkPut(step.loggedExercises.map((x: object) => ({ ...x, updatedAt: 1 })));
          await db.loggedSets.bulkPut(step.sets.map((x: object) => ({ ...x, updatedAt: 1 })));
          await db.mesocycles.bulkPut(step.mesocycles.map((m: { id: string; semanas: number; fechaInicio: number; deleted?: boolean }) => ({
            id: m.id, userId: null, nombre: 'Meso', objetivo: 'fuerza', semanas: m.semanas, diasPorSemana: 3, notas: null, progresion: [],
            fechaInicio: m.fechaInicio, updatedAt: 1, deletedAt: m.deleted ? 1 : null,
          })));
          const live = scenarioLiveBefore(scenario, step);
          await db.achievements.bulkPut(live.map((key: string) => ({ id: `row-${key}`, userId: null, clave: key, fechaDesbloqueo: 1, updatedAt: 1, deletedAt: null })));

          await inZone(scenario.zone, async () => {
            const metrics = await getLogroMetricas(scenario.weeklyTarget, step.now);
            expect({ ...metrics, volumenTotal: metrics.volumenTotal }).toEqual(step.expected.metrics);
            expect(evaluarLogros(metrics)).toEqual(step.expected.met);
            expect(await listPRs()).toEqual(step.expected.personalRecords);
            expect(await reconciliarLogros(scenario.weeklyTarget, step.now)).toEqual(step.expected.pwaNewlyUnlocked);
          });
        });
      }
    }
  });
});

/** Live claves of the stored rows before [step] (the previous step's storedAfter, or the scenario's initial rows). */
function scenarioLiveBefore(
  scenario: { initialStored: { key: string; state: string }[]; steps: { expected: { storedAfter: { key: string; state: string }[] } }[] },
  step: unknown,
): string[] {
  const index = scenario.steps.indexOf(step as never);
  const rows = index === 0 ? scenario.initialStored : scenario.steps[index - 1].expected.storedAfter;
  const injected = (step as { inject: { key: string; state: string }[] }).inject;
  return [...new Set([...rows, ...injected].filter((r) => r.state === 'live').map((r) => r.key))];
}
