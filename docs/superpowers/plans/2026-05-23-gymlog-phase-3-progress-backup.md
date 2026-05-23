# GymLog — Fase 3: Progreso, Historial y Backup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cerrar la app local-first: ver progreso por ejercicio (gráfica + récords + 1RM), historial de entrenos con racha, volumen por grupo muscular, y exportar/importar una copia de seguridad en JSON.

**Architecture:** Capa de cálculo pura `lib/repositories/stats.ts` (lee Dexie, sin estado de UI) que alimenta las pantallas de Historial y Progreso. Backup en `lib/repositories/backup.ts`. Gráficas con Recharts (única dependencia nueva). Se añade una 5ª pestaña "Ajustes". Todo offline, sin tocar el modelo de datos (lee lo de Fases 2A/2B).

**Tech Stack:** Next.js 16 · TS · Dexie · shadcn/ui · **Recharts** (nuevo) · Vitest + RTL + fake-indexeddb.

**Spec:** `docs/superpowers/specs/2026-05-23-gymlog-design.md` · **Fases previas:** 1, 2A, 2B.

**Fuera de alcance (diferido):** aviso "en vivo" al batir un PR durante el registro (se muestran los PRs en Progreso); cuenta + sync (Fase 4); reordenar; temas claro/oscuro configurables.

---

## File Structure

- `lib/repositories/stats.ts` — cálculos: 1RM, progreso por ejercicio, PRs, volumen por grupo, resúmenes de sesión, racha
- `lib/repositories/backup.ts` — export/import JSON
- `app/historial/page.tsx` — lista de entrenos + racha (reemplaza stub)
- `app/historial/[sessionId]/page.tsx` — detalle de una sesión (solo lectura) + borrar
- `components/session-summary-list.tsx` — lista de resúmenes de sesión
- `app/progreso/page.tsx` — selector de ejercicio + volumen por grupo (reemplaza stub)
- `components/exercise-progress.tsx` — PRs + gráfica de un ejercicio
- `components/exercise-chart.tsx` — gráfica de 1RM (Recharts)
- `app/ajustes/page.tsx` — exportar/importar copia
- `components/bottom-nav.tsx` — añadir pestaña Ajustes (grid-cols-5)
- `vitest.setup.ts` — polyfill de ResizeObserver (para Recharts en jsdom)
- Tests `*.test.ts(x)` junto al código

---

## Task 1: Repositorio de estadísticas

**Files:**
- Create: `lib/repositories/stats.ts`
- Test: `lib/repositories/stats.test.ts`

- [ ] **Step 1: Escribir los tests que fallan en `lib/repositories/stats.test.ts`**

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { startSession, addLoggedExercise, addSet } from '@/lib/repositories/workouts';
import {
  estimar1RM, getExerciseProgress, getExercisePRs, getVolumeByMuscle,
  listSessionSummaries, getCurrentStreakDays,
} from '@/lib/repositories/stats';

const DAY = 24 * 60 * 60 * 1000;

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.loggedSets.clear();
  await db.exercises.clear();
  await db.exercises.bulkPut([
    { id: 'seed-press-banca', userId: null, nombre: 'Press de banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 0, deletedAt: null },
    { id: 'seed-sentadilla', userId: null, nombre: 'Sentadilla', grupoMuscular: 'cuadriceps', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 0, deletedAt: null },
  ]);
});

// helper: crea una sesión con fecha concreta y unas series de un ejercicio
async function sesionCon(fecha: number, exerciseId: string, series: [number, number][]) {
  const s = await startSession({});
  await db.workoutSessions.update(s.id, { fecha });
  const le = await addLoggedExercise(s.id, exerciseId);
  for (const [peso, reps] of series) await addSet(le.id, { peso, reps });
  return s;
}

describe('estimar1RM (Epley)', () => {
  it('devuelve el peso para 1 rep y aplica Epley para más', () => {
    expect(estimar1RM(100, 1)).toBe(100);
    expect(estimar1RM(100, 10)).toBeCloseTo(133.3, 1);
  });
});

describe('getExerciseProgress', () => {
  it('agrega por sesión y ordena por fecha ascendente', async () => {
    await sesionCon(2 * DAY, 'seed-press-banca', [[60, 8], [62.5, 6]]);
    await sesionCon(5 * DAY, 'seed-press-banca', [[65, 8]]);
    const prog = await getExerciseProgress('seed-press-banca');
    expect(prog.map((p) => p.fecha)).toEqual([2 * DAY, 5 * DAY]);
    expect(prog[0].maxPeso).toBe(62.5);
    expect(prog[0].volumen).toBe(60 * 8 + 62.5 * 6);
    expect(prog[1].maxPeso).toBe(65);
  });
});

describe('getExercisePRs', () => {
  it('devuelve null si no hay datos y el máximo peso/1RM si los hay', async () => {
    expect(await getExercisePRs('seed-press-banca')).toBeNull();
    await sesionCon(1 * DAY, 'seed-press-banca', [[60, 5], [80, 1]]);
    const pr = await getExercisePRs('seed-press-banca');
    expect(pr).not.toBeNull();
    expect(pr!.maxPeso).toBe(80);
    expect(pr!.mejor1RM).toBeGreaterThanOrEqual(80);
  });
});

describe('getVolumeByMuscle', () => {
  it('suma el volumen por grupo muscular', async () => {
    await sesionCon(1 * DAY, 'seed-press-banca', [[60, 10]]); // pecho 600
    await sesionCon(1 * DAY, 'seed-sentadilla', [[100, 5]]); // cuadriceps 500
    const vol = await getVolumeByMuscle();
    const pecho = vol.find((v) => v.grupo === 'pecho');
    const cuads = vol.find((v) => v.grupo === 'cuadriceps');
    expect(pecho?.volumen).toBe(600);
    expect(cuads?.volumen).toBe(500);
  });

  it('respeta el filtro sinceTs', async () => {
    await sesionCon(1 * DAY, 'seed-press-banca', [[60, 10]]);
    const vol = await getVolumeByMuscle(2 * DAY);
    expect(vol).toHaveLength(0);
  });
});

describe('listSessionSummaries', () => {
  it('resume cada sesión (nº ejercicios y volumen), más reciente primero', async () => {
    await sesionCon(1 * DAY, 'seed-press-banca', [[60, 10]]);
    await sesionCon(3 * DAY, 'seed-sentadilla', [[100, 5]]);
    const res = await listSessionSummaries();
    expect(res).toHaveLength(2);
    expect(res[0].session.fecha).toBe(3 * DAY);
    expect(res[0].numEjercicios).toBe(1);
    expect(res[0].volumen).toBe(500);
  });
});

describe('getCurrentStreakDays', () => {
  it('es 0 sin sesiones', async () => {
    expect(await getCurrentStreakDays()).toBe(0);
  });
  it('cuenta 1 con una sesión hoy y no duplica dos el mismo día', async () => {
    await sesionCon(Date.now(), 'seed-press-banca', [[60, 5]]);
    await sesionCon(Date.now(), 'seed-sentadilla', [[100, 5]]);
    expect(await getCurrentStreakDays()).toBe(1);
  });
  it('es 0 si la única sesión es de hace días (racha rota)', async () => {
    await sesionCon(Date.now() - 10 * DAY, 'seed-press-banca', [[60, 5]]);
    expect(await getCurrentStreakDays()).toBe(0);
  });
});
```

- [ ] **Step 2: Ejecutar → FALLAN**

Run: `npx vitest run lib/repositories/stats.test.ts`

- [ ] **Step 3: Implementar `lib/repositories/stats.ts`**

```ts
import { db } from '@/lib/db/database';
import type { LoggedSet, MuscleGroup, WorkoutSession } from '@/lib/db/types';

const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

export function estimar1RM(peso: number, reps: number): number {
  if (reps <= 1) return peso;
  return Math.round(peso * (1 + reps / 30) * 10) / 10;
}

// Series activas de un ejercicio (sólo de sesiones no borradas) con la fecha de su sesión.
async function setsDeEjercicio(exerciseId: string): Promise<{ set: LoggedSet; fecha: number }[]> {
  const les = activo(await db.loggedExercises.where('exerciseId').equals(exerciseId).toArray());
  if (les.length === 0) return [];
  const sessionIds = [...new Set(les.map((le) => le.sessionId))];
  const sessions = await db.workoutSessions.bulkGet(sessionIds);
  const fechaBy = new Map<string, number>();
  for (const s of sessions) if (s && s.deletedAt === null) fechaBy.set(s.id, s.fecha);
  const out: { set: LoggedSet; fecha: number }[] = [];
  for (const le of les) {
    const fecha = fechaBy.get(le.sessionId);
    if (fecha === undefined) continue;
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    for (const set of sets) out.push({ set, fecha });
  }
  return out;
}

export interface ExerciseProgressPoint {
  fecha: number;
  maxPeso: number;
  mejor1RM: number;
  volumen: number;
}

export async function getExerciseProgress(exerciseId: string): Promise<ExerciseProgressPoint[]> {
  const data = await setsDeEjercicio(exerciseId);
  const byFecha = new Map<number, LoggedSet[]>();
  for (const { set, fecha } of data) {
    const arr = byFecha.get(fecha) ?? [];
    arr.push(set);
    byFecha.set(fecha, arr);
  }
  const points: ExerciseProgressPoint[] = [];
  for (const [fecha, sets] of byFecha) {
    points.push({
      fecha,
      maxPeso: Math.max(...sets.map((s) => s.peso)),
      mejor1RM: Math.max(...sets.map((s) => estimar1RM(s.peso, s.reps))),
      volumen: sets.reduce((acc, s) => acc + s.peso * s.reps, 0),
    });
  }
  return points.sort((a, b) => a.fecha - b.fecha);
}

export interface ExercisePRs {
  maxPeso: number;
  mejor1RM: number;
}

export async function getExercisePRs(exerciseId: string): Promise<ExercisePRs | null> {
  const data = await setsDeEjercicio(exerciseId);
  if (data.length === 0) return null;
  let maxPeso = 0;
  let mejor1RM = 0;
  for (const { set } of data) {
    maxPeso = Math.max(maxPeso, set.peso);
    mejor1RM = Math.max(mejor1RM, estimar1RM(set.peso, set.reps));
  }
  return { maxPeso, mejor1RM };
}

export interface VolumeByMuscle {
  grupo: MuscleGroup;
  volumen: number;
}

export async function getVolumeByMuscle(sinceTs = 0): Promise<VolumeByMuscle[]> {
  const sessions = activo(await db.workoutSessions.toArray()).filter((s) => s.fecha >= sinceTs);
  const sessionIds = new Set(sessions.map((s) => s.id));
  if (sessionIds.size === 0) return [];
  const les = activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  const exerciseIds = [...new Set(les.map((le) => le.exerciseId))];
  const exercises = await db.exercises.bulkGet(exerciseIds);
  const grupoBy = new Map<string, MuscleGroup>();
  for (const e of exercises) if (e) grupoBy.set(e.id, e.grupoMuscular);
  const volByGrupo = new Map<MuscleGroup, number>();
  for (const le of les) {
    const grupo = grupoBy.get(le.exerciseId);
    if (!grupo) continue;
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    const vol = sets.reduce((acc, s) => acc + s.peso * s.reps, 0);
    volByGrupo.set(grupo, (volByGrupo.get(grupo) ?? 0) + vol);
  }
  return [...volByGrupo.entries()]
    .map(([grupo, volumen]) => ({ grupo, volumen }))
    .sort((a, b) => b.volumen - a.volumen);
}

export interface SessionSummary {
  session: WorkoutSession;
  numEjercicios: number;
  volumen: number;
}

export async function listSessionSummaries(): Promise<SessionSummary[]> {
  const sessions = activo(await db.workoutSessions.toArray()).sort((a, b) => b.fecha - a.fecha);
  const out: SessionSummary[] = [];
  for (const session of sessions) {
    const les = activo(await db.loggedExercises.where('sessionId').equals(session.id).toArray());
    let volumen = 0;
    for (const le of les) {
      const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
      volumen += sets.reduce((acc, s) => acc + s.peso * s.reps, 0);
    }
    out.push({ session, numEjercicios: les.length, volumen });
  }
  return out;
}

const dayKey = (ts: number) => {
  const d = new Date(ts);
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
};

export async function getCurrentStreakDays(): Promise<number> {
  const sessions = activo(await db.workoutSessions.toArray());
  if (sessions.length === 0) return 0;
  const days = new Set(sessions.map((s) => dayKey(s.fecha)));
  let streak = 0;
  const cursor = new Date();
  while (days.has(dayKey(cursor.getTime()))) {
    streak++;
    cursor.setDate(cursor.getDate() - 1);
  }
  return streak;
}
```

- [ ] **Step 4: Ejecutar → PASAN**

Run: `npx vitest run lib/repositories/stats.test.ts`

- [ ] **Step 5: `npx tsc --noEmit` limpio · Commit**

```bash
git add -A && git commit -m "feat: add stats repository (1RM, progress, PRs, volume, summaries, streak)"
```

---

## Task 2: Historial (lista + racha) y detalle de sesión

**Files:**
- Create: `components/session-summary-list.tsx`
- Modify: `app/historial/page.tsx`
- Create: `app/historial/[sessionId]/page.tsx`
- Test: `components/session-summary-list.test.tsx`

- [ ] **Step 1: Escribir el test que falla en `components/session-summary-list.test.tsx`**

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { db } from '@/lib/db/database';
import { startSession, addLoggedExercise, addSet } from '@/lib/repositories/workouts';
import { SessionSummaryList } from '@/components/session-summary-list';

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.loggedSets.clear();
});

it('muestra aviso cuando no hay entrenos', async () => {
  render(<SessionSummaryList />);
  expect(await screen.findByText('Aún no has registrado entrenos.')).toBeInTheDocument();
});

it('lista los entrenos con su volumen', async () => {
  const s = await startSession({});
  const le = await addLoggedExercise(s.id, 'seed-press-banca');
  await addSet(le.id, { peso: 60, reps: 10 });
  render(<SessionSummaryList />);
  expect(await screen.findByText(/600/)).toBeInTheDocument();
});
```

- [ ] **Step 2: Ejecutar → FALLA**

Run: `npx vitest run components/session-summary-list.test.tsx`

- [ ] **Step 3: Implementar `components/session-summary-list.tsx`**

```tsx
'use client';

import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { listSessionSummaries } from '@/lib/repositories/stats';

export function SessionSummaryList() {
  const resumenes = useLiveQuery(() => listSessionSummaries(), []);

  if (resumenes === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (resumenes.length === 0) return <p className="text-muted-foreground">Aún no has registrado entrenos.</p>;

  return (
    <ul className="divide-y rounded-md border">
      {resumenes.map(({ session, numEjercicios, volumen }) => (
        <li key={session.id}>
          <Link href={`/historial/${session.id}`} className="flex items-center justify-between p-3">
            <div>
              <span className="font-medium">{new Date(session.fecha).toLocaleDateString('es-ES')}</span>
              <span className="block text-xs text-muted-foreground">{numEjercicios} ejercicios</span>
            </div>
            <span className="text-sm text-muted-foreground">{volumen} kg·rep</span>
          </Link>
        </li>
      ))}
    </ul>
  );
}
```

- [ ] **Step 4: Ejecutar → PASA**

- [ ] **Step 5: Reemplazar `app/historial/page.tsx`**

```tsx
'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { getCurrentStreakDays } from '@/lib/repositories/stats';
import { SessionSummaryList } from '@/components/session-summary-list';

export default function HistorialPage() {
  const racha = useLiveQuery(() => getCurrentStreakDays(), []);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Historial</h1>
        {racha !== undefined && racha > 0 && (
          <span className="text-sm font-medium text-primary">🔥 {racha} día{racha === 1 ? '' : 's'}</span>
        )}
      </div>
      <SessionSummaryList />
    </div>
  );
}
```

- [ ] **Step 6: Implementar `app/historial/[sessionId]/page.tsx`** (detalle solo lectura, usa `useParams()`)

```tsx
'use client';

import { useParams, useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import { getSession, listSessionExercises, softDeleteSession } from '@/lib/repositories/workouts';
import { Button } from '@/components/ui/button';

function ExerciseDetail({ loggedExerciseId, exerciseId }: { loggedExerciseId: string; exerciseId: string }) {
  const ejercicio = useLiveQuery(() => db.exercises.get(exerciseId), [exerciseId]);
  const sets = useLiveQuery(
    async () => (await db.loggedSets.where('loggedExerciseId').equals(loggedExerciseId).toArray()).filter((s) => s.deletedAt === null).sort((a, b) => a.orden - b.orden),
    [loggedExerciseId],
  );
  return (
    <div className="rounded-md border p-3">
      <p className="font-medium">{ejercicio?.nombre ?? '—'}</p>
      <ul className="mt-1 text-sm text-muted-foreground">
        {(sets ?? []).map((s, i) => (
          <li key={s.id}>{i + 1}. {s.peso} kg × {s.reps}</li>
        ))}
      </ul>
    </div>
  );
}

export default function SessionDetailPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const router = useRouter();
  const session = useLiveQuery(() => getSession(sessionId), [sessionId]);
  const ejercicios = useLiveQuery(() => listSessionExercises(sessionId), [sessionId]);

  if (session === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!session || session.deletedAt !== null) return <p>Entreno no encontrado.</p>;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{new Date(session.fecha).toLocaleDateString('es-ES')}</h1>
      <div className="space-y-3">
        {(ejercicios ?? []).map((le) => (
          <ExerciseDetail key={le.id} loggedExerciseId={le.id} exerciseId={le.exerciseId} />
        ))}
      </div>
      <Button
        variant="destructive"
        onClick={async () => {
          if (window.confirm('¿Borrar este entreno?')) {
            await softDeleteSession(sessionId);
            router.push('/historial');
          }
        }}
      >
        Borrar entreno
      </Button>
    </div>
  );
}
```

- [ ] **Step 7: Build + commit**

Run: `npm run build` → pasa.
```bash
git add -A && git commit -m "feat: history list with streak and read-only session detail"
```

---

## Task 3: Progreso (gráfica + PRs + volumen)

**Files:**
- Modify: `vitest.setup.ts` (polyfill ResizeObserver)
- Create: `components/exercise-chart.tsx`
- Create: `components/exercise-progress.tsx`
- Modify: `app/progreso/page.tsx`
- Test: `components/exercise-progress.test.tsx`

- [ ] **Step 1: Instalar Recharts**

```bash
npm install recharts
```
(Si hay conflicto de peer deps con React 19, reintenta con `--legacy-peer-deps`.)

- [ ] **Step 2: Añadir polyfill de ResizeObserver al final de `vitest.setup.ts`**

```ts
// Recharts (ResponsiveContainer) usa ResizeObserver, ausente en jsdom.
class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = globalThis.ResizeObserver ?? (ResizeObserverMock as unknown as typeof ResizeObserver);
```

- [ ] **Step 3: Escribir el test que falla en `components/exercise-progress.test.tsx`**

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { db } from '@/lib/db/database';
import { startSession, addLoggedExercise, addSet } from '@/lib/repositories/workouts';
import { ExerciseProgress } from '@/components/exercise-progress';

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.loggedSets.clear();
});

it('muestra los récords personales del ejercicio', async () => {
  const s = await startSession({});
  const le = await addLoggedExercise(s.id, 'seed-press-banca');
  await addSet(le.id, { peso: 80, reps: 1 });
  render(<ExerciseProgress exerciseId="seed-press-banca" />);
  expect(await screen.findByText('Máx. peso')).toBeInTheDocument();
  expect(await screen.findByText(/80/)).toBeInTheDocument();
});

it('avisa cuando no hay datos del ejercicio', async () => {
  render(<ExerciseProgress exerciseId="seed-press-banca" />);
  expect(await screen.findByText('Sin datos todavía para este ejercicio.')).toBeInTheDocument();
});
```

- [ ] **Step 4: Ejecutar → FALLA**

Run: `npx vitest run components/exercise-progress.test.tsx`

- [ ] **Step 5: Implementar `components/exercise-chart.tsx`**

```tsx
'use client';

import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { ExerciseProgressPoint } from '@/lib/repositories/stats';

export function ExerciseChart({ data }: { data: ExerciseProgressPoint[] }) {
  const puntos = data.map((p) => ({
    fecha: new Date(p.fecha).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit' }),
    mejor1RM: p.mejor1RM,
  }));
  return (
    <div className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={puntos} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
          <XAxis dataKey="fecha" fontSize={11} />
          <YAxis fontSize={11} />
          <Tooltip />
          <Line type="monotone" dataKey="mejor1RM" stroke="currentColor" className="text-primary" dot={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
```

- [ ] **Step 6: Implementar `components/exercise-progress.tsx`**

```tsx
'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { getExerciseProgress, getExercisePRs } from '@/lib/repositories/stats';
import { ExerciseChart } from '@/components/exercise-chart';

export function ExerciseProgress({ exerciseId }: { exerciseId: string }) {
  const progreso = useLiveQuery(() => getExerciseProgress(exerciseId), [exerciseId]);
  const prs = useLiveQuery(() => getExercisePRs(exerciseId), [exerciseId]);

  if (progreso === undefined || prs === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (prs === null) return <p className="text-muted-foreground">Sin datos todavía para este ejercicio.</p>;

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-2">
        <div className="rounded-md border p-3">
          <p className="text-xs text-muted-foreground">Máx. peso</p>
          <p className="text-lg font-bold">{prs.maxPeso} kg</p>
        </div>
        <div className="rounded-md border p-3">
          <p className="text-xs text-muted-foreground">Mejor 1RM est.</p>
          <p className="text-lg font-bold">{prs.mejor1RM} kg</p>
        </div>
      </div>
      {progreso.length > 1 && <ExerciseChart data={progreso} />}
    </div>
  );
}
```

- [ ] **Step 7: Ejecutar → PASA**

Run: `npx vitest run components/exercise-progress.test.tsx`

- [ ] **Step 8: Reemplazar `app/progreso/page.tsx`** (selector de ejercicio + volumen por grupo)

```tsx
'use client';

import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { listExercises } from '@/lib/repositories/exercises';
import { getVolumeByMuscle } from '@/lib/repositories/stats';
import { muscleGroupLabel } from '@/lib/labels';
import { ExerciseProgress } from '@/components/exercise-progress';

export default function ProgresoPage() {
  const ejercicios = useLiveQuery(() => listExercises(), []);
  const volumen = useLiveQuery(() => getVolumeByMuscle(), []);
  const [seleccion, setSeleccion] = useState('');

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Progreso</h1>

      <section className="space-y-2">
        <label htmlFor="ejercicio" className="text-sm font-semibold uppercase text-muted-foreground">
          Por ejercicio
        </label>
        <select
          id="ejercicio"
          className="w-full rounded-md border p-2"
          value={seleccion}
          onChange={(e) => setSeleccion(e.target.value)}
        >
          <option value="">Elige un ejercicio…</option>
          {(ejercicios ?? []).map((e) => (
            <option key={e.id} value={e.id}>{e.nombre}</option>
          ))}
        </select>
        {seleccion && <ExerciseProgress exerciseId={seleccion} />}
      </section>

      <section className="space-y-2">
        <h2 className="text-sm font-semibold uppercase text-muted-foreground">Volumen por grupo muscular</h2>
        {(volumen ?? []).length === 0 && <p className="text-muted-foreground">Aún no hay volumen registrado.</p>}
        <ul className="space-y-1">
          {(volumen ?? []).map((v) => (
            <li key={v.grupo} className="flex items-center justify-between text-sm">
              <span>{muscleGroupLabel[v.grupo]}</span>
              <span className="text-muted-foreground">{v.volumen} kg·rep</span>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
```

- [ ] **Step 9: Verificación + commit**

Run: `npm test` → verde. `npx tsc --noEmit` → limpio. `npm run build` → pasa.
```bash
git add -A && git commit -m "feat: progress view with per-exercise chart, PRs and volume by muscle"
```

---

## Task 4: Copia de seguridad (export/import) + Ajustes + pestaña

**Files:**
- Create: `lib/repositories/backup.ts`
- Test: `lib/repositories/backup.test.ts`
- Create: `app/ajustes/page.tsx`
- Modify: `components/bottom-nav.tsx` (añadir pestaña Ajustes, grid-cols-5)
- Modify: `components/bottom-nav.test.tsx` (5 pestañas)

- [ ] **Step 1: Escribir el test que falla en `lib/repositories/backup.test.ts`**

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { createRoutine, addDay } from '@/lib/repositories/routines';
import { startSession, addLoggedExercise, addSet } from '@/lib/repositories/workouts';
import { exportData, importData } from '@/lib/repositories/backup';

beforeEach(async () => {
  await Promise.all([
    db.exercises.clear(), db.routines.clear(), db.routineDays.clear(), db.routineExercises.clear(),
    db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(),
  ]);
});

it('exporta e importa todos los datos (roundtrip)', async () => {
  const r = await createRoutine({ nombre: 'R' });
  await addDay(r.id, { nombre: 'Día 1' });
  const s = await startSession({});
  const le = await addLoggedExercise(s.id, 'seed-press-banca');
  await addSet(le.id, { peso: 60, reps: 8 });

  const backup = await exportData();
  expect(backup.app).toBe('gymlog');
  expect(backup.data.routines).toHaveLength(1);

  await Promise.all([
    db.routines.clear(), db.routineDays.clear(), db.workoutSessions.clear(),
    db.loggedExercises.clear(), db.loggedSets.clear(),
  ]);
  expect(await db.routines.count()).toBe(0);

  await importData(backup);
  expect(await db.routines.count()).toBe(1);
  expect(await db.routineDays.count()).toBe(1);
  expect(await db.loggedSets.count()).toBe(1);
});

it('rechaza un fichero no válido', async () => {
  await expect(importData({ app: 'otra-cosa' } as never)).rejects.toThrow();
});
```

- [ ] **Step 2: Ejecutar → FALLA**

Run: `npx vitest run lib/repositories/backup.test.ts`

- [ ] **Step 3: Implementar `lib/repositories/backup.ts`**

```ts
import { db } from '@/lib/db/database';
import type {
  Exercise, Routine, RoutineDay, RoutineExercise,
  WorkoutSession, LoggedExercise, LoggedSet,
} from '@/lib/db/types';

export interface BackupFile {
  app: 'gymlog';
  version: number;
  exportedAt: number;
  data: {
    exercises: Exercise[];
    routines: Routine[];
    routineDays: RoutineDay[];
    routineExercises: RoutineExercise[];
    workoutSessions: WorkoutSession[];
    loggedExercises: LoggedExercise[];
    loggedSets: LoggedSet[];
  };
}

export async function exportData(): Promise<BackupFile> {
  return {
    app: 'gymlog',
    version: 3,
    exportedAt: Date.now(),
    data: {
      exercises: await db.exercises.toArray(),
      routines: await db.routines.toArray(),
      routineDays: await db.routineDays.toArray(),
      routineExercises: await db.routineExercises.toArray(),
      workoutSessions: await db.workoutSessions.toArray(),
      loggedExercises: await db.loggedExercises.toArray(),
      loggedSets: await db.loggedSets.toArray(),
    },
  };
}

export async function importData(backup: BackupFile): Promise<void> {
  if (!backup || backup.app !== 'gymlog' || !backup.data) {
    throw new Error('Fichero de copia no válido');
  }
  const d = backup.data;
  await db.transaction(
    'rw',
    db.exercises, db.routines, db.routineDays, db.routineExercises,
    db.workoutSessions, db.loggedExercises, db.loggedSets,
    async () => {
      if (d.exercises?.length) await db.exercises.bulkPut(d.exercises);
      if (d.routines?.length) await db.routines.bulkPut(d.routines);
      if (d.routineDays?.length) await db.routineDays.bulkPut(d.routineDays);
      if (d.routineExercises?.length) await db.routineExercises.bulkPut(d.routineExercises);
      if (d.workoutSessions?.length) await db.workoutSessions.bulkPut(d.workoutSessions);
      if (d.loggedExercises?.length) await db.loggedExercises.bulkPut(d.loggedExercises);
      if (d.loggedSets?.length) await db.loggedSets.bulkPut(d.loggedSets);
    },
  );
}
```

- [ ] **Step 4: Ejecutar → PASA**

Run: `npx vitest run lib/repositories/backup.test.ts`

- [ ] **Step 5: Implementar `app/ajustes/page.tsx`**

```tsx
'use client';

import { useRef, useState } from 'react';
import { exportData, importData, type BackupFile } from '@/lib/repositories/backup';
import { Button } from '@/components/ui/button';

export default function AjustesPage() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [mensaje, setMensaje] = useState('');

  async function exportar() {
    const backup = await exportData();
    const blob = new Blob([JSON.stringify(backup, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `gymlog-backup-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
    setMensaje('Copia exportada.');
  }

  async function importar(file: File) {
    try {
      const backup = JSON.parse(await file.text()) as BackupFile;
      await importData(backup);
      setMensaje('Copia importada correctamente.');
    } catch {
      setMensaje('No se pudo importar: fichero no válido.');
    }
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Ajustes</h1>

      <section className="space-y-3">
        <h2 className="text-sm font-semibold uppercase text-muted-foreground">Copia de seguridad</h2>
        <Button onClick={exportar} className="w-full">Exportar copia (JSON)</Button>
        <Button variant="secondary" className="w-full" onClick={() => inputRef.current?.click()}>
          Importar copia
        </Button>
        <input
          ref={inputRef}
          type="file"
          accept="application/json"
          className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) void importar(file);
          }}
        />
        {mensaje && <p className="text-sm text-muted-foreground">{mensaje}</p>}
      </section>
    </div>
  );
}
```

- [ ] **Step 6: Añadir la pestaña Ajustes en `components/bottom-nav.tsx`**

En el array `TABS` añade al final `{ href: '/ajustes', label: 'Ajustes' }`, y cambia la clase del `<nav>` de `grid-cols-4` a `grid-cols-5`.

- [ ] **Step 7: Actualizar `components/bottom-nav.test.tsx`**

En el test "muestra las cuatro pestañas" renómbralo a "muestra las cinco pestañas" y añade:
```tsx
    expect(screen.getByText('Ajustes')).toBeInTheDocument();
```
(mantén las otras cuatro aserciones y el resto de tests intactos).

- [ ] **Step 8: Verificación final**

Run: `npm test` → todo verde.
Run: `npx tsc --noEmit` → limpio.
Run: `npm run lint` → sin errores ni warnings.
Run: `npm run build` → pasa; rutas nuevas `/historial/[sessionId]` y `/ajustes`.

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "feat: JSON backup export/import + Ajustes tab"
```

---

## Self-Review (cobertura de la spec — Fase 3)

- **Gráfica por ejercicio (peso / mejor serie / 1RM)** → Tasks 1, 3 (`getExerciseProgress` + `ExerciseChart`) ✅
- **Récords personales (PRs)** → Tasks 1, 3 (`getExercisePRs` + tarjetas) ✅
- **Historial + calendario + rachas** → Tasks 1, 2 (`listSessionSummaries`, `getCurrentStreakDays`, detalle) ✅ (lista cronológica + racha; "calendario" como lista por fecha)
- **Volumen por grupo muscular** → Tasks 1, 3 (`getVolumeByMuscle`) ✅
- **Exportar/importar JSON** → Task 4 ✅
- **Tests de repositorio y de componente** → Tasks 1, 2, 3, 4 ✅
- **Diferido (correcto):** aviso "en vivo" al batir PR durante el registro (se muestran en Progreso); calendario visual con cuadrícula (se usa lista por fecha); cuenta + sync (Fase 4); temas/unidades configurables.

Sin placeholders. Nombres/firmas consistentes (`estimar1RM`, `getExerciseProgress`, `getExercisePRs`, `getVolumeByMuscle`, `listSessionSummaries`, `getCurrentStreakDays`, `exportData`, `importData`).

## Notas para Fase 4

- El export/import JSON usa los mismos registros con `id`/`updatedAt`/`deletedAt`; el sync de la nube reutilizará ese shape (push/pull + LWW).
- Considerar unificar el patrón de params (`useParams()`) en las páginas de Fases 1/2A al tocar auth.
