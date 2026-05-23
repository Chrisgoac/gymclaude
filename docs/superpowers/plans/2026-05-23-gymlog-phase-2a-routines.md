# GymLog — Fase 2A: Rutinas / Programas — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir crear y editar rutinas/programas: una rutina con varias sesiones (días) y, en cada día, ejercicios del catálogo con objetivo de series, reps y descanso.

**Architecture:** Sigue el patrón de la Fase 1. Se amplía el modelo Dexie a la versión 2 con tres tablas nuevas (`routines`, `routineDays`, `routineExercises`), cada registro con `SyncMeta` (`id`/`updatedAt`/`deletedAt`). Repositorio dedicado en `lib/repositories/routines.ts` con borrado lógico en cascada. UI mobile-first en la pestaña Rutinas, reutilizando shadcn/ui y `useLiveQuery`.

**Tech Stack:** Next.js 16 · TS · Dexie · shadcn/ui · Vitest + RTL + fake-indexeddb. (Sin cambios de dependencias.)

**Spec:** `docs/superpowers/specs/2026-05-23-gymlog-design.md` · **Fase previa:** `docs/superpowers/plans/2026-05-23-gymlog-phase-1-foundation.md`

**Fuera de alcance (diferido):** registro de entrenos y autorrelleno (Fase 2B); precarga del programa "Baby Groot" (fase posterior, requiere ampliar el catálogo); reordenar días/ejercicios por arrastre (se usa orden incremental).

---

## File Structure

- `lib/db/types.ts` — añadir `Routine`, `RoutineDay`, `RoutineExercise`
- `lib/db/database.ts` — añadir `version(2)` con las 3 tablas
- `lib/repositories/routines.ts` — CRUD de rutinas/días/ejercicios-de-día (+ cascada)
- `app/rutinas/page.tsx` — lista de rutinas (reemplaza el stub)
- `app/rutinas/nueva/page.tsx` — crear rutina
- `app/rutinas/[id]/page.tsx` — editor de la rutina (días)
- `app/rutinas/dia/[dayId]/page.tsx` — editor del día (ejercicios + objetivos)
- `app/rutinas/dia/[dayId]/anadir/page.tsx` — selector de ejercicio del catálogo
- `components/routine-day-exercise-row.tsx` — fila editable de ejercicio dentro de un día
- Tests `*.test.ts(x)` junto al código

---

## Task 1: Tipos del dominio + Dexie v2

**Files:**
- Modify: `lib/db/types.ts`
- Modify: `lib/db/database.ts`
- Test: `lib/db/database.test.ts` (añadir casos)

- [ ] **Step 1: Añadir tipos en `lib/db/types.ts`** (al final del archivo, sin tocar lo existente)

```ts
export interface Routine extends SyncMeta {
  userId: string | null;
  nombre: string;
  descripcion?: string;
  archivada: boolean;
}

export interface RoutineDay extends SyncMeta {
  routineId: string;
  nombre: string;
  orden: number;
  notas?: string;
}

export interface RoutineExercise extends SyncMeta {
  routineDayId: string;
  exerciseId: string;
  orden: number;
  seriesObjetivo?: number;
  repsObjetivo?: number;
  descansoSegundos?: number;
  notas?: string;
}
```

- [ ] **Step 2: Escribir el test que falla en `lib/db/database.test.ts`** (añadir dentro del `describe` existente)

```ts
  it('expone las tablas de rutinas (v2)', () => {
    expect(db.routines).toBeDefined();
    expect(db.routineDays).toBeDefined();
    expect(db.routineExercises).toBeDefined();
  });
```

- [ ] **Step 3: Ejecutar y ver que falla**

Run: `npx vitest run lib/db/database.test.ts`
Expected: FAIL (`db.routines` undefined).

- [ ] **Step 4: Implementar `version(2)` en `lib/db/database.ts`**

Reemplaza el contenido por (mantiene v1 para permitir migración de BD existentes):

```ts
import Dexie, { type Table } from 'dexie';
import type { Exercise, Routine, RoutineDay, RoutineExercise } from './types';

export class GymLogDB extends Dexie {
  exercises!: Table<Exercise, string>;
  routines!: Table<Routine, string>;
  routineDays!: Table<RoutineDay, string>;
  routineExercises!: Table<RoutineExercise, string>;

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
  }
}

export const db = new GymLogDB();
```

- [ ] **Step 5: Ejecutar y ver que pasa**

Run: `npx vitest run lib/db/database.test.ts`
Expected: PASS.

- [ ] **Step 6: Typecheck + commit**

Run: `npx tsc --noEmit` → sin errores.
```bash
git add -A && git commit -m "feat: add routine domain types and Dexie v2 schema"
```

---

## Task 2: Repositorio de rutinas

**Files:**
- Create: `lib/repositories/routines.ts`
- Test: `lib/repositories/routines.test.ts`

- [ ] **Step 1: Escribir los tests que fallan en `lib/repositories/routines.test.ts`**

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import {
  createRoutine, listRoutines, getRoutine, updateRoutine, softDeleteRoutine,
  addDay, listDays, updateDay, softDeleteDay,
  addExerciseToDay, listDayExercises, updateRoutineExercise, softDeleteRoutineExercise,
} from '@/lib/repositories/routines';

beforeEach(async () => {
  await db.routines.clear();
  await db.routineDays.clear();
  await db.routineExercises.clear();
});

describe('rutinas', () => {
  it('crea una rutina activa y la lista', async () => {
    const r = await createRoutine({ nombre: 'Full Body' });
    expect(r.id).toBeTruthy();
    expect(r.archivada).toBe(false);
    expect(r.deletedAt).toBeNull();
    expect((await listRoutines()).map((x) => x.nombre)).toEqual(['Full Body']);
    expect(await getRoutine(r.id)).toMatchObject({ nombre: 'Full Body' });
  });

  it('actualiza nombre/descripcion y refresca updatedAt', async () => {
    const r = await createRoutine({ nombre: 'A' });
    await new Promise((res) => setTimeout(res, 2));
    await updateRoutine(r.id, { nombre: 'B', descripcion: 'mi plan' });
    const after = await getRoutine(r.id);
    expect(after).toMatchObject({ nombre: 'B', descripcion: 'mi plan' });
    expect(after!.updatedAt).toBeGreaterThan(r.updatedAt);
  });

  it('borra en cascada la rutina, sus días y los ejercicios de esos días', async () => {
    const r = await createRoutine({ nombre: 'PPL' });
    const d = await addDay(r.id, { nombre: 'Empuje' });
    const re = await addExerciseToDay(d.id, { exerciseId: 'seed-press-banca', seriesObjetivo: 3, repsObjetivo: 8 });
    await softDeleteRoutine(r.id);
    expect(await listRoutines()).toHaveLength(0);
    expect(await listDays(r.id)).toHaveLength(0);
    expect(await listDayExercises(d.id)).toHaveLength(0);
    // siguen existiendo como tombstone
    expect((await getRoutine(r.id))!.deletedAt).not.toBeNull();
    expect((await db.routineExercises.get(re.id))!.deletedAt).not.toBeNull();
  });
});

describe('días', () => {
  it('añade días con orden incremental y los lista por orden', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const d1 = await addDay(r.id, { nombre: 'Día 1' });
    const d2 = await addDay(r.id, { nombre: 'Día 2' });
    expect(d1.orden).toBe(0);
    expect(d2.orden).toBe(1);
    expect((await listDays(r.id)).map((d) => d.nombre)).toEqual(['Día 1', 'Día 2']);
  });

  it('borra un día en cascada con sus ejercicios', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const d = await addDay(r.id, { nombre: 'D' });
    await addExerciseToDay(d.id, { exerciseId: 'seed-sentadilla' });
    await softDeleteDay(d.id);
    expect(await listDays(r.id)).toHaveLength(0);
    expect(await listDayExercises(d.id)).toHaveLength(0);
  });
});

describe('ejercicios del día', () => {
  it('añade, actualiza objetivos y borra', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const d = await addDay(r.id, { nombre: 'D' });
    const re = await addExerciseToDay(d.id, { exerciseId: 'seed-press-banca' });
    expect(re.orden).toBe(0);
    await updateRoutineExercise(re.id, { seriesObjetivo: 4, repsObjetivo: 10, descansoSegundos: 120 });
    expect(await db.routineExercises.get(re.id)).toMatchObject({ seriesObjetivo: 4, repsObjetivo: 10, descansoSegundos: 120 });
    await softDeleteRoutineExercise(re.id);
    expect(await listDayExercises(d.id)).toHaveLength(0);
  });
});
```

- [ ] **Step 2: Ejecutar y ver que fallan**

Run: `npx vitest run lib/repositories/routines.test.ts`
Expected: FAIL (módulo inexistente).

- [ ] **Step 3: Implementar `lib/repositories/routines.ts`**

```ts
import { db } from '@/lib/db/database';
import type { Routine, RoutineDay, RoutineExercise } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

// --- Rutinas ---
export async function createRoutine(input: { nombre: string; descripcion?: string }): Promise<Routine> {
  const routine: Routine = {
    id: crypto.randomUUID(),
    userId: null,
    nombre: input.nombre,
    descripcion: input.descripcion,
    archivada: false,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.routines.put(routine);
  return routine;
}

export function getRoutine(id: string): Promise<Routine | undefined> {
  return db.routines.get(id);
}

export async function listRoutines(): Promise<Routine[]> {
  const all = await db.routines.toArray();
  return activo(all).sort((a, b) => a.nombre.localeCompare(b.nombre, 'es', { sensitivity: 'base' }));
}

export async function updateRoutine(
  id: string,
  changes: Partial<Pick<Routine, 'nombre' | 'descripcion' | 'archivada'>>,
): Promise<void> {
  await db.routines.update(id, { ...changes, updatedAt: now() });
}

export async function softDeleteRoutine(id: string): Promise<void> {
  const ts = now();
  const days = await db.routineDays.where('routineId').equals(id).toArray();
  const dayIds = days.map((d) => d.id);
  await db.transaction('rw', db.routines, db.routineDays, db.routineExercises, async () => {
    await db.routines.update(id, { deletedAt: ts, updatedAt: ts });
    for (const d of days) await db.routineDays.update(d.id, { deletedAt: ts, updatedAt: ts });
    if (dayIds.length) {
      const res = await db.routineExercises.where('routineDayId').anyOf(dayIds).toArray();
      for (const re of res) await db.routineExercises.update(re.id, { deletedAt: ts, updatedAt: ts });
    }
  });
}

// --- Días ---
export async function addDay(routineId: string, input: { nombre: string }): Promise<RoutineDay> {
  const existentes = activo(await db.routineDays.where('routineId').equals(routineId).toArray());
  const day: RoutineDay = {
    id: crypto.randomUUID(),
    routineId,
    nombre: input.nombre,
    orden: existentes.length,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.routineDays.put(day);
  return day;
}

export async function listDays(routineId: string): Promise<RoutineDay[]> {
  const all = await db.routineDays.where('routineId').equals(routineId).toArray();
  return activo(all).sort((a, b) => a.orden - b.orden);
}

export async function updateDay(id: string, changes: Partial<Pick<RoutineDay, 'nombre' | 'notas'>>): Promise<void> {
  await db.routineDays.update(id, { ...changes, updatedAt: now() });
}

export async function softDeleteDay(id: string): Promise<void> {
  const ts = now();
  await db.transaction('rw', db.routineDays, db.routineExercises, async () => {
    await db.routineDays.update(id, { deletedAt: ts, updatedAt: ts });
    const res = await db.routineExercises.where('routineDayId').equals(id).toArray();
    for (const re of res) await db.routineExercises.update(re.id, { deletedAt: ts, updatedAt: ts });
  });
}

// --- Ejercicios del día ---
export async function addExerciseToDay(
  routineDayId: string,
  input: {
    exerciseId: string;
    seriesObjetivo?: number;
    repsObjetivo?: number;
    descansoSegundos?: number;
    notas?: string;
  },
): Promise<RoutineExercise> {
  const existentes = activo(await db.routineExercises.where('routineDayId').equals(routineDayId).toArray());
  const re: RoutineExercise = {
    id: crypto.randomUUID(),
    routineDayId,
    exerciseId: input.exerciseId,
    orden: existentes.length,
    seriesObjetivo: input.seriesObjetivo,
    repsObjetivo: input.repsObjetivo,
    descansoSegundos: input.descansoSegundos,
    notas: input.notas,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.routineExercises.put(re);
  return re;
}

export async function listDayExercises(routineDayId: string): Promise<RoutineExercise[]> {
  const all = await db.routineExercises.where('routineDayId').equals(routineDayId).toArray();
  return activo(all).sort((a, b) => a.orden - b.orden);
}

export async function updateRoutineExercise(
  id: string,
  changes: Partial<Pick<RoutineExercise, 'seriesObjetivo' | 'repsObjetivo' | 'descansoSegundos' | 'notas'>>,
): Promise<void> {
  await db.routineExercises.update(id, { ...changes, updatedAt: now() });
}

export async function softDeleteRoutineExercise(id: string): Promise<void> {
  const ts = now();
  await db.routineExercises.update(id, { deletedAt: ts, updatedAt: ts });
}
```

- [ ] **Step 4: Ejecutar y ver que pasan**

Run: `npx vitest run lib/repositories/routines.test.ts`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: add routines repository (routines, days, day-exercises with cascade soft delete)"
```

---

## Task 3: Lista de rutinas + crear rutina

**Files:**
- Modify: `app/rutinas/page.tsx` (reemplaza el stub)
- Create: `app/rutinas/nueva/page.tsx`
- Create: `components/routine-list.tsx`
- Test: `components/routine-list.test.tsx`

- [ ] **Step 1: Escribir el test que falla en `components/routine-list.test.tsx`**

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { db } from '@/lib/db/database';
import { createRoutine } from '@/lib/repositories/routines';
import { RoutineList } from '@/components/routine-list';

describe('RoutineList', () => {
  beforeEach(async () => {
    await db.routines.clear();
  });

  it('muestra el aviso cuando no hay rutinas', async () => {
    render(<RoutineList />);
    expect(await screen.findByText('Aún no tienes rutinas.')).toBeInTheDocument();
  });

  it('lista las rutinas existentes', async () => {
    await createRoutine({ nombre: 'Full Body' });
    await createRoutine({ nombre: 'Push Pull Legs' });
    render(<RoutineList />);
    expect(await screen.findByText('Full Body')).toBeInTheDocument();
    expect(screen.getByText('Push Pull Legs')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Ejecutar y ver que falla**

Run: `npx vitest run components/routine-list.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implementar `components/routine-list.tsx`**

```tsx
'use client';

import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { listRoutines } from '@/lib/repositories/routines';

export function RoutineList() {
  const routines = useLiveQuery(() => listRoutines(), []);

  if (routines === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (routines.length === 0) return <p className="text-muted-foreground">Aún no tienes rutinas.</p>;

  return (
    <ul className="divide-y rounded-md border">
      {routines.map((r) => (
        <li key={r.id}>
          <Link href={`/rutinas/${r.id}`} className="block p-3">
            <span className="font-medium">{r.nombre}</span>
            {r.descripcion && <span className="block text-xs text-muted-foreground">{r.descripcion}</span>}
          </Link>
        </li>
      ))}
    </ul>
  );
}
```

- [ ] **Step 4: Ejecutar y ver que pasa**

Run: `npx vitest run components/routine-list.test.tsx`
Expected: PASS (2).

- [ ] **Step 5: Reemplazar `app/rutinas/page.tsx`**

```tsx
import Link from 'next/link';
import { RoutineList } from '@/components/routine-list';

export default function RutinasPage() {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Rutinas</h1>
        <Link href="/rutinas/nueva" className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground">
          Nueva
        </Link>
      </div>
      <RoutineList />
    </div>
  );
}
```

- [ ] **Step 6: Crear `app/rutinas/nueva/page.tsx`**

```tsx
'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { createRoutine } from '@/lib/repositories/routines';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Button } from '@/components/ui/button';

export default function NuevaRutinaPage() {
  const router = useRouter();
  const [nombre, setNombre] = useState('');
  const [descripcion, setDescripcion] = useState('');
  const [error, setError] = useState('');

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (nombre.trim() === '') {
      setError('El nombre es obligatorio');
      return;
    }
    const r = await createRoutine({ nombre: nombre.trim(), descripcion: descripcion.trim() || undefined });
    router.push(`/rutinas/${r.id}`);
  }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Nueva rutina</h1>
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-1">
          <Label htmlFor="nombre">Nombre</Label>
          <Input id="nombre" value={nombre} onChange={(e) => setNombre(e.target.value)} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="descripcion">Descripción (opcional)</Label>
          <Textarea id="descripcion" value={descripcion} onChange={(e) => setDescripcion(e.target.value)} />
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
        <Button type="submit">Crear</Button>
      </form>
    </div>
  );
}
```

- [ ] **Step 7: Build + commit**

Run: `npm run build` → pasa.
```bash
git add -A && git commit -m "feat: routines list and create-routine screen"
```

---

## Task 4: Editor de rutina (gestión de días)

**Files:**
- Create: `app/rutinas/[id]/page.tsx`
- Test: `app/rutinas/[id]/page.test.tsx`

- [ ] **Step 1: Escribir el test que falla en `app/rutinas/[id]/page.test.tsx`**

Usamos `<Suspense>` porque la página usa `use(params)`.

```tsx
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { Suspense } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { createRoutine, listDays } from '@/lib/repositories/routines';
import RoutineEditorPage from '@/app/rutinas/[id]/page';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

beforeEach(async () => {
  await db.routines.clear();
  await db.routineDays.clear();
  await db.routineExercises.clear();
});

it('añade un día a la rutina', async () => {
  const r = await createRoutine({ nombre: 'R' });
  render(
    <Suspense>
      <RoutineEditorPage params={Promise.resolve({ id: r.id })} />
    </Suspense>,
  );
  expect(await screen.findByText('R')).toBeInTheDocument();
  await userEvent.type(screen.getByPlaceholderText('Nombre del día'), 'Empuje');
  await userEvent.click(screen.getByRole('button', { name: 'Añadir día' }));
  expect(await screen.findByText('Empuje')).toBeInTheDocument();
  expect(await listDays(r.id)).toHaveLength(1);
});
```

- [ ] **Step 2: Ejecutar y ver que falla**

Run: `npx vitest run app/rutinas/[id]/page.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implementar `app/rutinas/[id]/page.tsx`**

```tsx
'use client';

import { use, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import { addDay, listDays, softDeleteDay, softDeleteRoutine } from '@/lib/repositories/routines';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';

export default function RoutineEditorPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const router = useRouter();
  const [nuevoDia, setNuevoDia] = useState('');

  const routine = useLiveQuery(() => db.routines.get(id), [id]);
  const dias = useLiveQuery(() => listDays(id), [id]);

  if (routine === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!routine || routine.deletedAt !== null) return <p>Rutina no encontrada.</p>;

  async function añadir() {
    if (nuevoDia.trim() === '') return;
    await addDay(id, { nombre: nuevoDia.trim() });
    setNuevoDia('');
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{routine.nombre}</h1>
        {routine.descripcion && <p className="text-sm text-muted-foreground">{routine.descripcion}</p>}
      </div>

      <section className="space-y-2">
        <h2 className="text-sm font-semibold uppercase text-muted-foreground">Días</h2>
        {(dias ?? []).length === 0 && <p className="text-muted-foreground">Aún no hay días.</p>}
        <ul className="divide-y rounded-md border">
          {(dias ?? []).map((d) => (
            <li key={d.id} className="flex items-center justify-between p-3">
              <Link href={`/rutinas/dia/${d.id}`} className="font-medium">
                {d.nombre}
              </Link>
              <button
                className="text-xs text-destructive"
                onClick={async () => {
                  if (window.confirm(`¿Borrar el día "${d.nombre}"?`)) await softDeleteDay(d.id);
                }}
              >
                Borrar
              </button>
            </li>
          ))}
        </ul>
        <div className="flex gap-2">
          <Input
            placeholder="Nombre del día"
            value={nuevoDia}
            onChange={(e) => setNuevoDia(e.target.value)}
          />
          <Button type="button" onClick={añadir}>
            Añadir día
          </Button>
        </div>
      </section>

      <Button
        variant="destructive"
        onClick={async () => {
          if (window.confirm(`¿Borrar la rutina "${routine.nombre}" y todos sus días?`)) {
            await softDeleteRoutine(id);
            router.push('/rutinas');
          }
        }}
      >
        Borrar rutina
      </Button>
    </div>
  );
}
```

- [ ] **Step 4: Ejecutar y ver que pasa**

Run: `npx vitest run app/rutinas/[id]/page.test.tsx`
Expected: PASS.

- [ ] **Step 5: Build + commit**

Run: `npm run build` → pasa.
```bash
git add -A && git commit -m "feat: routine editor with day management"
```

---

## Task 5: Editor del día (ejercicios + objetivos) y selector del catálogo

**Files:**
- Create: `app/rutinas/dia/[dayId]/page.tsx`
- Create: `app/rutinas/dia/[dayId]/anadir/page.tsx`
- Create: `components/routine-day-exercise-row.tsx`
- Test: `components/routine-day-exercise-row.test.tsx`

- [ ] **Step 1: Escribir el test que falla en `components/routine-day-exercise-row.test.tsx`**

La fila muestra el nombre del ejercicio y permite editar series/reps/descanso (persisten al cambiar).

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { createRoutine, addDay, addExerciseToDay } from '@/lib/repositories/routines';
import { RoutineDayExerciseRow } from '@/components/routine-day-exercise-row';

beforeEach(async () => {
  await db.routines.clear();
  await db.routineDays.clear();
  await db.routineExercises.clear();
  await db.exercises.clear();
  await db.exercises.put({
    id: 'seed-press-banca', userId: null, nombre: 'Press de banca', grupoMuscular: 'pecho',
    equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 0, deletedAt: null,
  });
});

it('muestra el nombre del ejercicio y guarda los objetivos', async () => {
  const r = await createRoutine({ nombre: 'R' });
  const d = await addDay(r.id, { nombre: 'D' });
  const re = await addExerciseToDay(d.id, { exerciseId: 'seed-press-banca' });

  render(<RoutineDayExerciseRow routineExercise={re} />);
  expect(await screen.findByText('Press de banca')).toBeInTheDocument();

  await userEvent.type(screen.getByLabelText('Series'), '3');
  await userEvent.type(screen.getByLabelText('Reps'), '8');
  await waitFor(async () => {
    const stored = await db.routineExercises.get(re.id);
    expect(stored?.seriesObjetivo).toBe(3);
    expect(stored?.repsObjetivo).toBe(8);
  });
});
```

- [ ] **Step 2: Ejecutar y ver que falla**

Run: `npx vitest run components/routine-day-exercise-row.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implementar `components/routine-day-exercise-row.tsx`**

```tsx
'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import type { RoutineExercise } from '@/lib/db/types';
import { updateRoutineExercise, softDeleteRoutineExercise } from '@/lib/repositories/routines';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';

function parseNum(v: string): number | undefined {
  const n = Number(v);
  return v.trim() === '' || Number.isNaN(n) ? undefined : n;
}

export function RoutineDayExerciseRow({ routineExercise }: { routineExercise: RoutineExercise }) {
  const ejercicio = useLiveQuery(() => db.exercises.get(routineExercise.exerciseId), [routineExercise.exerciseId]);

  return (
    <li className="space-y-2 p-3">
      <div className="flex items-center justify-between">
        <span className="font-medium">{ejercicio?.nombre ?? '—'}</span>
        <button
          className="text-xs text-destructive"
          onClick={() => softDeleteRoutineExercise(routineExercise.id)}
        >
          Quitar
        </button>
      </div>
      <div className="grid grid-cols-3 gap-2">
        <div className="space-y-1">
          <Label htmlFor={`series-${routineExercise.id}`} className="text-xs">Series</Label>
          <Input
            id={`series-${routineExercise.id}`}
            inputMode="numeric"
            defaultValue={routineExercise.seriesObjetivo ?? ''}
            onChange={(e) => updateRoutineExercise(routineExercise.id, { seriesObjetivo: parseNum(e.target.value) })}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor={`reps-${routineExercise.id}`} className="text-xs">Reps</Label>
          <Input
            id={`reps-${routineExercise.id}`}
            inputMode="numeric"
            defaultValue={routineExercise.repsObjetivo ?? ''}
            onChange={(e) => updateRoutineExercise(routineExercise.id, { repsObjetivo: parseNum(e.target.value) })}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor={`descanso-${routineExercise.id}`} className="text-xs">Descanso (s)</Label>
          <Input
            id={`descanso-${routineExercise.id}`}
            inputMode="numeric"
            defaultValue={routineExercise.descansoSegundos ?? ''}
            onChange={(e) => updateRoutineExercise(routineExercise.id, { descansoSegundos: parseNum(e.target.value) })}
          />
        </div>
      </div>
    </li>
  );
}
```

- [ ] **Step 4: Ejecutar y ver que pasa**

Run: `npx vitest run components/routine-day-exercise-row.test.tsx`
Expected: PASS.

- [ ] **Step 5: Implementar `app/rutinas/dia/[dayId]/page.tsx`**

```tsx
'use client';

import { use } from 'react';
import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import { listDayExercises } from '@/lib/repositories/routines';
import { RoutineDayExerciseRow } from '@/components/routine-day-exercise-row';

export default function DayEditorPage({ params }: { params: Promise<{ dayId: string }> }) {
  const { dayId } = use(params);
  const dia = useLiveQuery(() => db.routineDays.get(dayId), [dayId]);
  const ejercicios = useLiveQuery(() => listDayExercises(dayId), [dayId]);

  if (dia === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!dia || dia.deletedAt !== null) return <p>Día no encontrado.</p>;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">{dia.nombre}</h1>
        <Link
          href={`/rutinas/dia/${dayId}/anadir`}
          className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground"
        >
          Añadir ejercicio
        </Link>
      </div>
      {(ejercicios ?? []).length === 0 && <p className="text-muted-foreground">Aún no hay ejercicios.</p>}
      <ul className="divide-y rounded-md border">
        {(ejercicios ?? []).map((re) => (
          <RoutineDayExerciseRow key={re.id} routineExercise={re} />
        ))}
      </ul>
      <Link href={`/rutinas/${dia.routineId}`} className="text-primary underline">
        Volver a la rutina
      </Link>
    </div>
  );
}
```

- [ ] **Step 6: Implementar el selector `app/rutinas/dia/[dayId]/anadir/page.tsx`**

Reutiliza la consulta del catálogo; al tocar un ejercicio lo añade al día y vuelve.

```tsx
'use client';

import { use, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { listExercises } from '@/lib/repositories/exercises';
import { addExerciseToDay } from '@/lib/repositories/routines';
import { equipmentLabel } from '@/lib/labels';
import { Input } from '@/components/ui/input';

export default function AnadirEjercicioPage({ params }: { params: Promise<{ dayId: string }> }) {
  const { dayId } = use(params);
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
                await addExerciseToDay(dayId, { exerciseId: e.id });
                router.push(`/rutinas/dia/${dayId}`);
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
Run: `npm run build` → pasa; rutas nuevas `/rutinas/[id]`, `/rutinas/dia/[dayId]`, `/rutinas/dia/[dayId]/anadir`, `/rutinas/nueva`.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: day editor with exercise targets and catalog picker"
```

---

## Self-Review (cobertura de la spec — Fase 2A)

- **Rutina con varias sesiones (días)** → Tasks 1, 2, 4 ✅
- **Cada día: ejercicios con series/reps/descanso/notas objetivo** → Tasks 2, 5 ✅
- **Crear/editar/borrar rutinas y días (borrado lógico en cascada para sync)** → Tasks 2, 3, 4 ✅
- **Añadir ejercicios desde el catálogo** → Task 5 ✅
- **`SyncMeta` en todas las entidades nuevas** → Task 1 ✅
- **Tests de repositorio y de componente** → Tasks 2, 3, 4, 5 ✅
- **Diferido (correcto):** registro de entrenos/autorrelleno (2B), precarga "Baby Groot", reordenar por arrastre, archivar rutinas (campo presente, sin UI).

Sin placeholders. Nombres/firmas consistentes (`createRoutine`, `addDay`, `addExerciseToDay`, `listDayExercises`, `updateRoutineExercise`, `softDeleteRoutine/Day/RoutineExercise`).

## Notas para Fase 2B y posteriores

- Registro de entrenos (`WorkoutSession`, `LoggedExercise`, `LoggedSet`) referenciará `routineDayId` para precargar los ejercicios objetivo del día.
- La precarga de "Baby Groot" necesitará primero ampliar el catálogo con sus ejercicios concretos (incline bench, meadows row, etc.).
