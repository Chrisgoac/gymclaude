# GymLog — Fase 2B: Registro de entrenos — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Registrar entrenos: empezar una sesión (libre o desde un día de rutina, que se precarga con sus ejercicios), apuntar series **peso × reps** rápido con autorrelleno de la última vez, y finalizar guardando el entreno con fecha.

**Architecture:** Continúa el patrón local-first. Dexie sube a versión 3 con `workoutSessions`, `loggedExercises`, `loggedSets` (todas con `SyncMeta`). Repositorio `lib/repositories/workouts.ts` (depende de `routines` solo para precargar ejercicios de un día). UI mobile-first: pestaña Entrenar para empezar, y pantalla de registro `/entrenar/[sessionId]`. Las páginas dinámicas usan `useParams()` (más limpio y testeable que `use(params)`).

**Tech Stack:** Next.js 16 · TS · Dexie · shadcn/ui · Vitest + RTL + fake-indexeddb. (Sin cambios de dependencias.)

**Spec:** `docs/superpowers/specs/2026-05-23-gymlog-design.md` · **Fases previas:** plan-1-foundation, plan-2a-routines.

**Fuera de alcance (diferido):** vistas de progreso, historial y calendario (Fase 3); export/import (Fase 3); temporizador de descanso (era opcional, no en el registro "simple"); RPE/series feeder (decisión: registro simple).

---

## File Structure

- `lib/db/types.ts` — añadir `WorkoutSession`, `LoggedExercise`, `LoggedSet`
- `lib/db/database.ts` — `version(3)` con las 3 tablas
- `lib/repositories/workouts.ts` — sesiones, ejercicios registrados, series, autorrelleno
- `app/page.tsx` — pestaña Entrenar: empezar entreno (libre o desde rutina)
- `components/start-workout.tsx` — opciones para empezar
- `app/entrenar/[sessionId]/page.tsx` — pantalla de registro
- `app/entrenar/[sessionId]/anadir/page.tsx` — selector de ejercicio del catálogo
- `components/logged-exercise-card.tsx` — tarjeta de un ejercicio con sus series
- Tests `*.test.ts(x)` junto al código

---

## Task 1: Tipos + Dexie v3

**Files:**
- Modify: `lib/db/types.ts`
- Modify: `lib/db/database.ts`
- Test: `lib/db/database.test.ts` (añadir caso)

- [ ] **Step 1: Añadir tipos al final de `lib/db/types.ts`**

```ts
export interface WorkoutSession extends SyncMeta {
  userId: string | null;
  routineDayId?: string; // vacío = entreno libre
  fecha: number; // epoch ms (inicio del entreno)
  duracionSegundos?: number;
  notas?: string;
}

export interface LoggedExercise extends SyncMeta {
  sessionId: string;
  exerciseId: string;
  orden: number;
}

export interface LoggedSet extends SyncMeta {
  loggedExerciseId: string;
  orden: number;
  peso: number;
  reps: number;
  esCalentamiento?: boolean;
}
```

- [ ] **Step 2: Añadir test en `lib/db/database.test.ts`** (dentro del `describe` existente)

```ts
  it('expone las tablas de registro de entrenos (v3)', () => {
    expect(db.workoutSessions).toBeDefined();
    expect(db.loggedExercises).toBeDefined();
    expect(db.loggedSets).toBeDefined();
  });
```

- [ ] **Step 3: Ejecutar → FALLA**

Run: `npx vitest run lib/db/database.test.ts`

- [ ] **Step 4: Implementar `version(3)` en `lib/db/database.ts`**

Reemplaza el archivo manteniendo v1 y v2 y añadiendo v3:

```ts
import Dexie, { type Table } from 'dexie';
import type {
  Exercise, Routine, RoutineDay, RoutineExercise,
  WorkoutSession, LoggedExercise, LoggedSet,
} from './types';

export class GymLogDB extends Dexie {
  exercises!: Table<Exercise, string>;
  routines!: Table<Routine, string>;
  routineDays!: Table<RoutineDay, string>;
  routineExercises!: Table<RoutineExercise, string>;
  workoutSessions!: Table<WorkoutSession, string>;
  loggedExercises!: Table<LoggedExercise, string>;
  loggedSets!: Table<LoggedSet, string>;

  constructor() {
    super('gymlog');
    this.version(1).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
    });
    this.version(2).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineDayId, exerciseId, orden, deletedAt',
    });
    this.version(3).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineDayId, exerciseId, orden, deletedAt',
      workoutSessions: 'id, userId, routineDayId, fecha, deletedAt',
      loggedExercises: 'id, sessionId, exerciseId, orden, deletedAt',
      loggedSets: 'id, loggedExerciseId, orden, deletedAt',
    });
  }
}

export const db = new GymLogDB();
```

- [ ] **Step 5: Ejecutar → PASA** · `npx tsc --noEmit` limpio

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: add workout-logging domain types and Dexie v3 schema"
```

---

## Task 2: Repositorio de registro de entrenos

**Files:**
- Create: `lib/repositories/workouts.ts`
- Test: `lib/repositories/workouts.test.ts`

- [ ] **Step 1: Escribir los tests que fallan en `lib/repositories/workouts.test.ts`**

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { createRoutine, addDay, addExerciseToDay } from '@/lib/repositories/routines';
import {
  startSession, getSession, listSessions, finishSession,
  addLoggedExercise, listSessionExercises, softDeleteLoggedExercise,
  addSet, updateSet, softDeleteSet, listExerciseSets, getLastSet,
} from '@/lib/repositories/workouts';

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.loggedSets.clear();
  await db.routines.clear();
  await db.routineDays.clear();
  await db.routineExercises.clear();
});

describe('sesiones', () => {
  it('empieza un entreno libre vacío', async () => {
    const s = await startSession({});
    expect(s.id).toBeTruthy();
    expect(s.routineDayId).toBeUndefined();
    expect(s.fecha).toBeGreaterThan(0);
    expect(await listSessionExercises(s.id)).toHaveLength(0);
  });

  it('empieza desde un día de rutina y precarga sus ejercicios en orden', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const d = await addDay(r.id, { nombre: 'Empuje' });
    await addExerciseToDay(d.id, { exerciseId: 'seed-press-banca' });
    await addExerciseToDay(d.id, { exerciseId: 'seed-press-militar' });
    const s = await startSession({ routineDayId: d.id });
    const les = await listSessionExercises(s.id);
    expect(les.map((le) => le.exerciseId)).toEqual(['seed-press-banca', 'seed-press-militar']);
  });

  it('finaliza la sesión guardando duración y notas', async () => {
    const s = await startSession({});
    await new Promise((res) => setTimeout(res, 5));
    await finishSession(s.id, { notas: 'buen día' });
    const after = await getSession(s.id);
    expect(after?.notas).toBe('buen día');
    expect(after?.duracionSegundos).toBeGreaterThanOrEqual(0);
  });

  it('lista las sesiones no borradas de más reciente a más antigua', async () => {
    const a = await startSession({});
    await new Promise((res) => setTimeout(res, 3));
    const b = await startSession({});
    const ids = (await listSessions()).map((x) => x.id);
    expect(ids).toEqual([b.id, a.id]);
  });
});

describe('ejercicios y series', () => {
  it('añade series con orden incremental y las edita/borra', async () => {
    const s = await startSession({});
    const le = await addLoggedExercise(s.id, 'seed-press-banca');
    const set1 = await addSet(le.id, { peso: 60, reps: 8 });
    const set2 = await addSet(le.id, { peso: 60, reps: 7 });
    expect(set1.orden).toBe(0);
    expect(set2.orden).toBe(1);
    await updateSet(set1.id, { reps: 9 });
    expect((await db.loggedSets.get(set1.id))?.reps).toBe(9);
    await softDeleteSet(set2.id);
    expect(await listExerciseSets(le.id)).toHaveLength(1);
  });

  it('borra un ejercicio registrado en cascada con sus series', async () => {
    const s = await startSession({});
    const le = await addLoggedExercise(s.id, 'seed-press-banca');
    await addSet(le.id, { peso: 60, reps: 8 });
    await softDeleteLoggedExercise(le.id);
    expect(await listSessionExercises(s.id)).toHaveLength(0);
    expect(await listExerciseSets(le.id)).toHaveLength(0);
  });
});

describe('autorrelleno', () => {
  it('getLastSet devuelve la última serie del mismo ejercicio en una sesión anterior', async () => {
    const vieja = await startSession({});
    const leVieja = await addLoggedExercise(vieja.id, 'seed-sentadilla');
    await addSet(leVieja.id, { peso: 80, reps: 5 });
    await addSet(leVieja.id, { peso: 85, reps: 5 });
    await new Promise((res) => setTimeout(res, 3));
    const nueva = await startSession({});
    const last = await getLastSet('seed-sentadilla', nueva.id);
    expect(last).toMatchObject({ peso: 85, reps: 5 });
  });

  it('getLastSet ignora la sesión excluida y devuelve undefined si no hay histórico', async () => {
    const s = await startSession({});
    const le = await addLoggedExercise(s.id, 'seed-sentadilla');
    await addSet(le.id, { peso: 100, reps: 3 });
    expect(await getLastSet('seed-sentadilla', s.id)).toBeUndefined();
  });
});
```

- [ ] **Step 2: Ejecutar → FALLAN**

Run: `npx vitest run lib/repositories/workouts.test.ts`

- [ ] **Step 3: Implementar `lib/repositories/workouts.ts`**

```ts
import { db } from '@/lib/db/database';
import type { WorkoutSession, LoggedExercise, LoggedSet } from '@/lib/db/types';
import { listDayExercises } from '@/lib/repositories/routines';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

// --- Sesiones ---
export async function startSession(input: { routineDayId?: string }): Promise<WorkoutSession> {
  const session: WorkoutSession = {
    id: crypto.randomUUID(),
    userId: null,
    routineDayId: input.routineDayId,
    fecha: now(),
    updatedAt: now(),
    deletedAt: null,
  };
  await db.workoutSessions.put(session);
  if (input.routineDayId) {
    const dayExercises = await listDayExercises(input.routineDayId);
    for (const re of dayExercises) {
      await addLoggedExercise(session.id, re.exerciseId);
    }
  }
  return session;
}

export function getSession(id: string): Promise<WorkoutSession | undefined> {
  return db.workoutSessions.get(id);
}

export async function listSessions(): Promise<WorkoutSession[]> {
  const all = await db.workoutSessions.toArray();
  return activo(all).sort((a, b) => b.fecha - a.fecha);
}

export async function finishSession(id: string, input: { notas?: string }): Promise<void> {
  const session = await db.workoutSessions.get(id);
  if (!session) return;
  const duracionSegundos = Math.round((now() - session.fecha) / 1000);
  await db.workoutSessions.update(id, { duracionSegundos, notas: input.notas, updatedAt: now() });
}

export async function softDeleteSession(id: string): Promise<void> {
  const ts = now();
  await db.transaction('rw', db.workoutSessions, db.loggedExercises, db.loggedSets, async () => {
    await db.workoutSessions.update(id, { deletedAt: ts, updatedAt: ts });
    const les = activo(await db.loggedExercises.where('sessionId').equals(id).toArray());
    for (const le of les) {
      await db.loggedExercises.update(le.id, { deletedAt: ts, updatedAt: ts });
      const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
      for (const set of sets) await db.loggedSets.update(set.id, { deletedAt: ts, updatedAt: ts });
    }
  });
}

// --- Ejercicios registrados ---
export async function addLoggedExercise(sessionId: string, exerciseId: string): Promise<LoggedExercise> {
  const existentes = activo(await db.loggedExercises.where('sessionId').equals(sessionId).toArray());
  const le: LoggedExercise = {
    id: crypto.randomUUID(),
    sessionId,
    exerciseId,
    orden: existentes.length,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.loggedExercises.put(le);
  return le;
}

export async function listSessionExercises(sessionId: string): Promise<LoggedExercise[]> {
  const all = await db.loggedExercises.where('sessionId').equals(sessionId).toArray();
  return activo(all).sort((a, b) => a.orden - b.orden);
}

export async function softDeleteLoggedExercise(id: string): Promise<void> {
  const ts = now();
  await db.transaction('rw', db.loggedExercises, db.loggedSets, async () => {
    await db.loggedExercises.update(id, { deletedAt: ts, updatedAt: ts });
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(id).toArray());
    for (const set of sets) await db.loggedSets.update(set.id, { deletedAt: ts, updatedAt: ts });
  });
}

// --- Series ---
export async function addSet(loggedExerciseId: string, input: { peso: number; reps: number; esCalentamiento?: boolean }): Promise<LoggedSet> {
  const existentes = activo(await db.loggedSets.where('loggedExerciseId').equals(loggedExerciseId).toArray());
  const set: LoggedSet = {
    id: crypto.randomUUID(),
    loggedExerciseId,
    orden: existentes.length,
    peso: input.peso,
    reps: input.reps,
    esCalentamiento: input.esCalentamiento,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.loggedSets.put(set);
  return set;
}

export async function updateSet(id: string, changes: Partial<Pick<LoggedSet, 'peso' | 'reps' | 'esCalentamiento'>>): Promise<void> {
  await db.loggedSets.update(id, { ...changes, updatedAt: now() });
}

export async function softDeleteSet(id: string): Promise<void> {
  const ts = now();
  await db.loggedSets.update(id, { deletedAt: ts, updatedAt: ts });
}

export async function listExerciseSets(loggedExerciseId: string): Promise<LoggedSet[]> {
  const all = await db.loggedSets.where('loggedExerciseId').equals(loggedExerciseId).toArray();
  return activo(all).sort((a, b) => a.orden - b.orden);
}

// Última serie registrada de un ejercicio en una sesión distinta (para autorrelleno).
export async function getLastSet(exerciseId: string, excludeSessionId?: string): Promise<LoggedSet | undefined> {
  const les = activo(await db.loggedExercises.where('exerciseId').equals(exerciseId).toArray())
    .filter((le) => le.sessionId !== excludeSessionId);
  if (les.length === 0) return undefined;
  const sessionIds = [...new Set(les.map((le) => le.sessionId))];
  const sessions = await db.workoutSessions.bulkGet(sessionIds);
  const fechaBySession = new Map<string, number>();
  for (const s of sessions) if (s) fechaBySession.set(s.id, s.fecha);
  les.sort((a, b) => (fechaBySession.get(b.sessionId) ?? 0) - (fechaBySession.get(a.sessionId) ?? 0));
  for (const le of les) {
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    if (sets.length > 0) {
      sets.sort((a, b) => b.orden - a.orden);
      return sets[0];
    }
  }
  return undefined;
}
```

- [ ] **Step 4: Ejecutar → PASAN** (8 tests)

Run: `npx vitest run lib/repositories/workouts.test.ts`

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: add workouts repository (sessions, logged exercises/sets, autofill)"
```

---

## Task 3: Pantalla Entrenar (empezar entreno)

**Files:**
- Create: `components/start-workout.tsx`
- Modify: `app/page.tsx`
- Test: `components/start-workout.test.tsx`

- [ ] **Step 1: Escribir el test que falla en `components/start-workout.test.tsx`**

```tsx
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { StartWorkout } from '@/components/start-workout';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.routines.clear();
  await db.routineDays.clear();
  push.mockClear();
});

it('empieza un entreno libre y navega a la sesión', async () => {
  render(<StartWorkout />);
  await userEvent.click(screen.getByRole('button', { name: 'Empezar entreno libre' }));
  await waitFor(() => expect(push).toHaveBeenCalled());
  expect(await db.workoutSessions.count()).toBe(1);
  const sessionId = (await db.workoutSessions.toArray())[0].id;
  expect(push).toHaveBeenCalledWith(`/entrenar/${sessionId}`);
});
```

- [ ] **Step 2: Ejecutar → FALLA**

Run: `npx vitest run components/start-workout.test.tsx`

- [ ] **Step 3: Implementar `components/start-workout.tsx`**

```tsx
'use client';

import { useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { listRoutines, listDays } from '@/lib/repositories/routines';
import { startSession } from '@/lib/repositories/workouts';
import type { Routine } from '@/lib/db/types';
import { Button } from '@/components/ui/button';

export function StartWorkout() {
  const router = useRouter();
  const routines = useLiveQuery(() => listRoutines(), []);

  async function empezarLibre() {
    const s = await startSession({});
    router.push(`/entrenar/${s.id}`);
  }

  async function empezarDia(routineDayId: string) {
    const s = await startSession({ routineDayId });
    router.push(`/entrenar/${s.id}`);
  }

  return (
    <div className="space-y-6">
      <Button onClick={empezarLibre} className="w-full">
        Empezar entreno libre
      </Button>

      {(routines ?? []).length > 0 && (
        <section className="space-y-3">
          <h2 className="text-sm font-semibold uppercase text-muted-foreground">Desde una rutina</h2>
          {(routines ?? []).map((r) => (
            <RoutineDaysToStart key={r.id} routine={r} onStart={empezarDia} />
          ))}
        </section>
      )}
    </div>
  );
}

function RoutineDaysToStart({ routine, onStart }: { routine: Routine; onStart: (dayId: string) => void }) {
  const dias = useLiveQuery(() => listDays(routine.id), [routine.id]);
  if ((dias ?? []).length === 0) return null;
  return (
    <div className="rounded-md border p-3">
      <p className="mb-2 font-medium">{routine.nombre}</p>
      <ul className="space-y-1">
        {(dias ?? []).map((d) => (
          <li key={d.id} className="flex items-center justify-between">
            <span className="text-sm">{d.nombre}</span>
            <button className="text-sm text-primary" onClick={() => onStart(d.id)}>
              Empezar
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
```

- [ ] **Step 4: Ejecutar → PASA**

- [ ] **Step 5: Reemplazar `app/page.tsx`**

```tsx
import { StartWorkout } from '@/components/start-workout';

export default function EntrenarPage() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Entrenar</h1>
      <StartWorkout />
    </div>
  );
}
```

- [ ] **Step 6: Build + commit**

Run: `npm run build` → pasa.
```bash
git add -A && git commit -m "feat: Entrenar tab to start a free or routine-based workout"
```

---

## Task 4: Pantalla de registro + tarjeta de ejercicio + selector

**Files:**
- Create: `components/logged-exercise-card.tsx`
- Create: `app/entrenar/[sessionId]/page.tsx`
- Create: `app/entrenar/[sessionId]/anadir/page.tsx`
- Test: `components/logged-exercise-card.test.tsx`

- [ ] **Step 1: Escribir el test que falla en `components/logged-exercise-card.test.tsx`**

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { startSession, addLoggedExercise, listExerciseSets } from '@/lib/repositories/workouts';
import { LoggedExerciseCard } from '@/components/logged-exercise-card';

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.loggedSets.clear();
  await db.exercises.clear();
  await db.exercises.put({
    id: 'seed-press-banca', userId: null, nombre: 'Press de banca', grupoMuscular: 'pecho',
    equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 0, deletedAt: null,
  });
});

it('muestra el ejercicio y añade una serie con peso y reps', async () => {
  const s = await startSession({});
  const le = await addLoggedExercise(s.id, 'seed-press-banca');

  render(<LoggedExerciseCard loggedExercise={le} sessionId={s.id} />);
  expect(await screen.findByText('Press de banca')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'Añadir serie' }));
  // aparece una fila de serie editable; rellenamos peso y reps
  const pesos = await screen.findAllByLabelText('Peso');
  await userEvent.clear(pesos[0]);
  await userEvent.type(pesos[0], '60');
  const reps = screen.getAllByLabelText('Reps');
  await userEvent.clear(reps[0]);
  await userEvent.type(reps[0], '8');

  await waitFor(async () => {
    const sets = await listExerciseSets(le.id);
    expect(sets).toHaveLength(1);
    expect(sets[0]).toMatchObject({ peso: 60, reps: 8 });
  });
});
```

- [ ] **Step 2: Ejecutar → FALLA**

Run: `npx vitest run components/logged-exercise-card.test.tsx`

- [ ] **Step 3: Implementar `components/logged-exercise-card.tsx`**

```tsx
'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import type { LoggedExercise } from '@/lib/db/types';
import {
  addSet, updateSet, softDeleteSet, listExerciseSets, getLastSet, softDeleteLoggedExercise,
} from '@/lib/repositories/workouts';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';

function parseNum(v: string): number {
  const n = Number(v);
  return v.trim() === '' || Number.isNaN(n) ? 0 : n;
}

export function LoggedExerciseCard({
  loggedExercise,
  sessionId,
}: {
  loggedExercise: LoggedExercise;
  sessionId: string;
}) {
  const ejercicio = useLiveQuery(() => db.exercises.get(loggedExercise.exerciseId), [loggedExercise.exerciseId]);
  const sets = useLiveQuery(() => listExerciseSets(loggedExercise.id), [loggedExercise.id]);

  async function añadirSerie() {
    const actuales = sets ?? [];
    if (actuales.length > 0) {
      const ultima = actuales[actuales.length - 1];
      await addSet(loggedExercise.id, { peso: ultima.peso, reps: ultima.reps });
      return;
    }
    const previa = await getLastSet(loggedExercise.exerciseId, sessionId);
    await addSet(loggedExercise.id, { peso: previa?.peso ?? 0, reps: previa?.reps ?? 0 });
  }

  return (
    <div className="space-y-3 rounded-md border p-3">
      <div className="flex items-center justify-between">
        <span className="font-medium">{ejercicio?.nombre ?? '—'}</span>
        <button className="text-xs text-destructive" onClick={() => softDeleteLoggedExercise(loggedExercise.id)}>
          Quitar
        </button>
      </div>

      <ul className="space-y-2">
        {(sets ?? []).map((set, i) => (
          <li key={set.id} className="flex items-end gap-2">
            <span className="w-5 pb-2 text-xs text-muted-foreground">{i + 1}</span>
            <div className="flex-1 space-y-1">
              <Label htmlFor={`peso-${set.id}`} className="text-xs">Peso</Label>
              <Input
                id={`peso-${set.id}`}
                inputMode="decimal"
                defaultValue={set.peso}
                onChange={(e) => updateSet(set.id, { peso: parseNum(e.target.value) })}
              />
            </div>
            <div className="flex-1 space-y-1">
              <Label htmlFor={`reps-${set.id}`} className="text-xs">Reps</Label>
              <Input
                id={`reps-${set.id}`}
                inputMode="numeric"
                defaultValue={set.reps}
                onChange={(e) => updateSet(set.id, { reps: parseNum(e.target.value) })}
              />
            </div>
            <button className="pb-2 text-xs text-destructive" onClick={() => softDeleteSet(set.id)}>
              ✕
            </button>
          </li>
        ))}
      </ul>

      <Button type="button" variant="secondary" className="w-full" onClick={añadirSerie}>
        Añadir serie
      </Button>
    </div>
  );
}
```

- [ ] **Step 4: Ejecutar → PASA**

- [ ] **Step 5: Implementar `app/entrenar/[sessionId]/page.tsx`** (usa `useParams()`)

```tsx
'use client';

import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { getSession, listSessionExercises, finishSession } from '@/lib/repositories/workouts';
import { LoggedExerciseCard } from '@/components/logged-exercise-card';
import { Button } from '@/components/ui/button';

export default function RegistroPage() {
  const params = useParams<{ sessionId: string }>();
  const sessionId = params.sessionId;
  const router = useRouter();

  const session = useLiveQuery(() => getSession(sessionId), [sessionId]);
  const ejercicios = useLiveQuery(() => listSessionExercises(sessionId), [sessionId]);

  if (session === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!session || session.deletedAt !== null) return <p>Entreno no encontrado.</p>;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Entreno</h1>
        <span className="text-sm text-muted-foreground">
          {new Date(session.fecha).toLocaleDateString('es-ES')}
        </span>
      </div>

      {(ejercicios ?? []).length === 0 && <p className="text-muted-foreground">Añade ejercicios para empezar.</p>}

      <div className="space-y-3">
        {(ejercicios ?? []).map((le) => (
          <LoggedExerciseCard key={le.id} loggedExercise={le} sessionId={sessionId} />
        ))}
      </div>

      <Link
        href={`/entrenar/${sessionId}/anadir`}
        className="block rounded-md border border-dashed p-3 text-center text-sm text-primary"
      >
        + Añadir ejercicio
      </Link>

      <Button
        className="w-full"
        onClick={async () => {
          await finishSession(sessionId, {});
          router.push('/');
        }}
      >
        Finalizar entreno
      </Button>
    </div>
  );
}
```

- [ ] **Step 6: Implementar `app/entrenar/[sessionId]/anadir/page.tsx`**

```tsx
'use client';

import { useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { listExercises } from '@/lib/repositories/exercises';
import { addLoggedExercise } from '@/lib/repositories/workouts';
import { equipmentLabel } from '@/lib/labels';
import { Input } from '@/components/ui/input';

export default function AnadirEjercicioEntrenoPage() {
  const params = useParams<{ sessionId: string }>();
  const sessionId = params.sessionId;
  const router = useRouter();
  const [query, setQuery] = useState('');
  const ejercicios = useLiveQuery(() => listExercises(), []);

  const filtrados = (ejercicios ?? []).filter((e) =>
    e.nombre.toLowerCase().includes(query.trim().toLowerCase()),
  );

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Añadir ejercicio</h1>
      <Input placeholder="Buscar ejercicio…" value={query} onChange={(e) => setQuery(e.target.value)} />
      <ul className="divide-y rounded-md border">
        {filtrados.map((e) => (
          <li key={e.id}>
            <button
              className="flex w-full items-center justify-between p-3 text-left"
              onClick={async () => {
                await addLoggedExercise(sessionId, e.id);
                router.push(`/entrenar/${sessionId}`);
              }}
            >
              <span>{e.nombre}</span>
              <span className="text-xs text-muted-foreground">{equipmentLabel[e.equipamiento]}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
```

- [ ] **Step 7: Verificación final**

Run: `npm test` → todo verde.
Run: `npx tsc --noEmit` → sin errores.
Run: `npm run lint` → sin errores ni warnings.
Run: `npm run build` → pasa; rutas nuevas `/entrenar/[sessionId]` y `/entrenar/[sessionId]/anadir`.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: workout logging screen with set tracking, autofill and exercise picker"
```

---

## Self-Review (cobertura de la spec — Fase 2B)

- **Empezar entreno desde un día de rutina (precarga ejercicios) o libre** → Tasks 2, 3 ✅
- **Registrar series peso × reps rápido** → Tasks 2, 4 ✅
- **Autorrelleno con lo de la última vez** → Task 2 (`getLastSet`) + Task 4 (al añadir serie) ✅
- **Finalizar guardando fecha/duración** → Tasks 2, 4 ✅
- **`SyncMeta` + borrado lógico en cascada (sesión→ejercicios→series)** → Tasks 1, 2 ✅
- **Tests de repositorio y de componente** → Tasks 2, 3, 4 ✅
- **Patrón de params unificado a `useParams()`** en las páginas nuevas → Task 4 ✅
- **Diferido (correcto):** progreso/historial/calendario y export/import (Fase 3), temporizador de descanso, RPE.

Sin placeholders. Nombres/firmas consistentes (`startSession`, `addLoggedExercise`, `addSet`, `updateSet`, `softDeleteSet`, `getLastSet`, `finishSession`, `listSessionExercises`, `listExerciseSets`).

## Notas para Fase 3 y posteriores

- La Fase 3 (progreso/historial) leerá `workoutSessions`/`loggedSets` para gráficas por ejercicio, PRs (1RM Epley), volumen y calendario; `softDeleteSession` ya está disponible para el historial.
- Considerar migrar las páginas dinámicas de Fase 1/2A a `useParams()` para unificar el patrón.
