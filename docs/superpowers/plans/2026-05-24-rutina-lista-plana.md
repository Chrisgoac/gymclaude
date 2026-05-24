# Rutina como lista plana (eliminar capa "día") — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que una rutina sea directamente una lista de ejercicios (`Routine → RoutineExercise`), eliminando la capa intermedia `RoutineDay`.

**Architecture:** Refactor en dos fases para mantener cada commit verde: **(aditiva)** se añade `routineId` a `RoutineExercise`, se migran datos en Dexie (v6 rellena `routineId` con la tabla `routineDays` aún viva) y se conmutan repo/UI/`startSession` al nuevo modelo; **(sustractiva)** se borra de golpe la capa día ya muerta (funciones, tipo `RoutineDay`, tabla Dexie v7, tabla Drizzle `routine_days`, columna `routine_day_id`, registros de sync, campos de backup) y se actualizan los tests. `WorkoutSession.routineDayId` queda como campo legacy sin uso.

**Tech Stack:** Next.js 16 (App Router, `--webpack`), React 19, Dexie (IndexedDB) con migraciones versionadas, Drizzle + Neon Postgres, Vitest + RTL + fake-indexeddb. Estética Brutalist Iron.

**Spec:** `docs/superpowers/specs/2026-05-24-rutina-lista-plana-design.md`

**Convención de migración Dexie:** los tests usan una BD fresca a la última versión, así que el `upgrade` (que solo corre al actualizar una BD existente) no se ejercita en los tests; su corrección se asegura por revisión de código. Los tests cubren el comportamiento del esquema final y de las funciones nuevas.

---

## File Structure

**Crear:**
- `app/rutinas/[id]/anadir/page.tsx` — buscador para añadir ejercicio a una rutina.

**Modificar:**
- `lib/db/types.ts` — `RoutineExercise.routineId`; quitar `RoutineDay` (fase sustractiva).
- `lib/db/database.ts` — Dexie v6 (backfill) y v7 (drop tabla).
- `lib/repositories/routines.ts` — funciones a nivel rutina; quitar funciones de día.
- `lib/repositories/workouts.ts` — `startSession({ routineId })`.
- `lib/repositories/backup.ts` — quitar `routineDays`.
- `lib/sync/{collect,apply,server-tables}.ts` — quitar `routineDays`.
- `db/schema.ts` — `routine_id`; quitar `routine_days` y `routine_day_id`.
- `app/rutinas/[id]/page.tsx` — editor de rutina como lista de ejercicios.
- `components/start-workout.tsx` — rutinas→Empezar→gimnasio→`startSession({ routineId })`.
- Tests: `lib/repositories/routines.test.ts`, `lib/repositories/workouts.test.ts`,
  `lib/repositories/backup.test.ts`, `lib/db/database.test.ts`,
  `components/routine-day-exercise-row.test.tsx`, `components/start-workout.test.tsx`,
  `app/rutinas/[id]/page.test.tsx`.

**Eliminar:**
- `app/rutinas/dia/[dayId]/page.tsx`, `app/rutinas/dia/[dayId]/anadir/page.tsx` (carpeta `app/rutinas/dia/`).

---

## Task 1: (Aditivo) `routineId` en RoutineExercise + migración Dexie v6

**Files:**
- Modify: `lib/db/types.ts`, `lib/db/database.ts`, `lib/repositories/routines.ts`
- Test: `lib/repositories/routines.test.ts`

- [ ] **Step 1: Añadir `routineId` al tipo (y hacer `routineDayId` opcional durante la transición)**

En `lib/db/types.ts`, en `RoutineExercise`:

```ts
export interface RoutineExercise extends SyncMeta {
  routineId: string;        // nuevo: el ejercicio cuelga directamente de la rutina
  routineDayId?: string;    // legacy en transición; se elimina en la fase sustractiva
  exerciseId: string;
  orden: number;
  seriesObjetivo?: number;
  repsObjetivo?: number;
  descansoSegundos?: number;
  notas?: string;
}
```

- [ ] **Step 2: Escribir el test (falla)**

En `lib/repositories/routines.test.ts`, dentro del `describe('ejercicios del día', ...)`, añade al final del test `'añade, actualiza objetivos y borra'` (tras crear `re`):

```ts
    expect(re.routineId).toBe(r.id); // el ejercicio del día también guarda routineId
```

- [ ] **Step 3: Ejecutar (debe fallar)**

Run: `npm run test -- routines.test.ts`
Expected: FAIL — `re.routineId` es `undefined`.

- [ ] **Step 4: Que `addExerciseToDay` rellene `routineId` + Dexie v6**

En `lib/repositories/routines.ts`, en `addExerciseToDay`, resuelve el `routineId` del día y guárdalo:

```ts
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
  const dia = await db.routineDays.get(routineDayId);
  const existentes = activo(await db.routineExercises.where('routineDayId').equals(routineDayId).toArray());
  const re: RoutineExercise = {
    id: crypto.randomUUID(),
    routineId: dia?.routineId ?? '',
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
```

En `lib/db/database.ts`, añade la versión 6 al final del constructor (después de `version(5)`). Mantiene `routineDays` viva y añade el índice `routineId`; el `upgrade` rellena `routineId` en los ejercicios existentes a partir de su día:

```ts
    this.version(6).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineId, routineDayId, exerciseId, orden, deletedAt',
      workoutSessions: 'id, userId, routineDayId, gymId, fecha, deletedAt',
      loggedExercises: 'id, sessionId, exerciseId, orden, deletedAt',
      loggedSets: 'id, loggedExerciseId, orden, deletedAt',
      syncState: 'key',
      gyms: 'id, userId, nombre, deletedAt',
    }).upgrade(async (tx) => {
      const days = await tx.table('routineDays').toArray();
      const routineIdByDay = new Map<string, string>(days.map((d) => [d.id, d.routineId]));
      await tx.table('routineExercises').toCollection().modify((re) => {
        re.routineId = routineIdByDay.get(re.routineDayId) ?? '';
        re.updatedAt = Date.now(); // re-sincroniza el cambio
      });
    });
```

- [ ] **Step 5: Ejecutar (debe pasar)**

Run: `npm run test -- routines.test.ts`
Expected: PASS

- [ ] **Step 6: Verificar suite + tipos + lint**

Run: `npm run test && npx tsc --noEmit && npm run lint`
Expected: todo verde (cambio puramente aditivo).

- [ ] **Step 7: Commit**

```bash
git add lib/db/types.ts lib/db/database.ts lib/repositories/routines.ts lib/repositories/routines.test.ts
git commit -m "feat(routines): routineId en RoutineExercise + migración Dexie v6 (aditivo)"
```

---

## Task 2: (Aditivo) Funciones a nivel rutina + `startSession({ routineId })`

**Files:**
- Modify: `lib/repositories/routines.ts`, `lib/repositories/workouts.ts`
- Test: `lib/repositories/routines.test.ts`, `lib/repositories/workouts.test.ts`

- [ ] **Step 1: Escribir los tests (fallan)**

En `lib/repositories/routines.test.ts`, añade al import de `@/lib/repositories/routines` los nombres `addExerciseToRoutine, listRoutineExercises`, y añade este bloque al final:

```ts
describe('ejercicios de la rutina (plano)', () => {
  it('añade ejercicios directos a la rutina con orden incremental', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const e1 = await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
    const e2 = await addExerciseToRoutine(r.id, { exerciseId: 'seed-sentadilla' });
    expect(e1.routineId).toBe(r.id);
    expect(e1.routineDayId).toBeUndefined();
    expect(e1.orden).toBe(0);
    expect(e2.orden).toBe(1);
    expect((await listRoutineExercises(r.id)).map((re) => re.exerciseId))
      .toEqual(['seed-press-banca', 'seed-sentadilla']);
  });

  it('listRoutineExercises omite los borrados', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const e1 = await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
    await softDeleteRoutineExercise(e1.id);
    expect(await listRoutineExercises(r.id)).toHaveLength(0);
  });
});
```

En `lib/repositories/workouts.test.ts`, añade al import de `@/lib/repositories/routines` (junto a `createRoutine`) `addExerciseToRoutine`, y añade este test al `describe('sesiones', ...)`:

```ts
  it('empieza desde una rutina y precarga sus ejercicios en orden', async () => {
    const r = await createRoutine({ nombre: 'R' });
    await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
    await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-militar' });
    const s = await startSession({ routineId: r.id });
    const les = await listSessionExercises(s.id);
    expect(les.map((le) => le.exerciseId)).toEqual(['seed-press-banca', 'seed-press-militar']);
  });
```

- [ ] **Step 2: Ejecutar (deben fallar)**

Run: `npm run test -- routines.test.ts workouts.test.ts`
Expected: FAIL — `addExerciseToRoutine`/`listRoutineExercises` no existen; `startSession({ routineId })` no precarga.

- [ ] **Step 3: Implementar las funciones de rutina**

En `lib/repositories/routines.ts`, añade (no quites aún las de día):

```ts
export async function addExerciseToRoutine(
  routineId: string,
  input: {
    exerciseId: string;
    seriesObjetivo?: number;
    repsObjetivo?: number;
    descansoSegundos?: number;
    notas?: string;
  },
): Promise<RoutineExercise> {
  const existentes = activo(await db.routineExercises.where('routineId').equals(routineId).toArray());
  const re: RoutineExercise = {
    id: crypto.randomUUID(),
    routineId,
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

export async function listRoutineExercises(routineId: string): Promise<RoutineExercise[]> {
  const all = await db.routineExercises.where('routineId').equals(routineId).toArray();
  return activo(all).sort((a, b) => a.orden - b.orden);
}
```

En `lib/repositories/workouts.ts`, amplía `startSession` para aceptar `routineId` y precargar con `listRoutineExercises` (mantén el camino `routineDayId` por ahora). Cambia el import y la función:

```ts
import { listDayExercises, listRoutineExercises } from '@/lib/repositories/routines';
```

```ts
export async function startSession(input: { routineDayId?: string; routineId?: string; gymId?: string | null }): Promise<WorkoutSession> {
  const ts = now();
  const session: WorkoutSession = {
    id: crypto.randomUUID(),
    userId: null,
    routineDayId: input.routineDayId,
    gymId: input.gymId ?? null,
    fecha: ts,
    updatedAt: ts,
    deletedAt: null,
  };
  await db.workoutSessions.put(session);
  if (input.routineId) {
    const res = await listRoutineExercises(input.routineId);
    for (const re of res) await addLoggedExercise(session.id, re.exerciseId);
  } else if (input.routineDayId) {
    const dayExercises = await listDayExercises(input.routineDayId);
    for (const re of dayExercises) await addLoggedExercise(session.id, re.exerciseId);
  }
  return session;
}
```

- [ ] **Step 4: Ejecutar (deben pasar)**

Run: `npm run test -- routines.test.ts workouts.test.ts`
Expected: PASS

- [ ] **Step 5: Verificar suite + tipos + lint**

Run: `npm run test && npx tsc --noEmit && npm run lint`
Expected: todo verde.

- [ ] **Step 6: Commit**

```bash
git add lib/repositories/routines.ts lib/repositories/workouts.ts lib/repositories/routines.test.ts lib/repositories/workouts.test.ts
git commit -m "feat(routines): addExerciseToRoutine/listRoutineExercises y startSession por rutina (aditivo)"
```

---

## Task 3: (UI) Editor de rutina como lista + página añadir + home + borrar rutas de día

**Files:**
- Modify: `app/rutinas/[id]/page.tsx`, `components/start-workout.tsx`
- Create: `app/rutinas/[id]/anadir/page.tsx`
- Delete: `app/rutinas/dia/[dayId]/page.tsx`, `app/rutinas/dia/[dayId]/anadir/page.tsx`
- Test: `app/rutinas/[id]/page.test.tsx`, `components/start-workout.test.tsx`

- [ ] **Step 1: Reescribir el test del editor de rutina (falla)**

Reemplaza `app/rutinas/[id]/page.test.tsx` por:

```tsx
import { it, expect, beforeEach, vi } from 'vitest';
import { Suspense } from 'react';
import { render, screen } from '@testing-library/react';
import { db } from '@/lib/db/database';
import { createRoutine, addExerciseToRoutine } from '@/lib/repositories/routines';
import RoutineEditorPage from '@/app/rutinas/[id]/page';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

beforeEach(async () => {
  await db.routines.clear();
  await db.routineExercises.clear();
  await db.exercises.clear();
  await db.exercises.put({
    id: 'seed-press-banca', userId: null, nombre: 'Press de banca', grupoMuscular: 'pecho',
    equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 0, deletedAt: null,
  });
});

it('muestra los ejercicios de la rutina', async () => {
  const r = await createRoutine({ nombre: 'R' });
  await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
  render(
    <Suspense>
      <RoutineEditorPage params={Promise.resolve({ id: r.id })} />
    </Suspense>,
  );
  expect(await screen.findByText('R')).toBeInTheDocument();
  expect(await screen.findByText('Press de banca')).toBeInTheDocument();
});
```

- [ ] **Step 2: Ejecutar (debe fallar)**

Run: `npm run test` (suite completa; las rutas con corchetes no se pueden filtrar de forma fiable)
Expected: FAIL en el test reescrito del editor de rutina — el editor aún muestra "Días", no la lista de ejercicios. (El resto sigue verde.)

- [ ] **Step 3: Reescribir el editor de rutina**

Reemplaza `app/rutinas/[id]/page.tsx` por (lista de ejercicios + Añadir ejercicio, estética Brutalist Iron):

```tsx
'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import { listRoutineExercises, softDeleteRoutine } from '@/lib/repositories/routines';
import { RoutineDayExerciseRow } from '@/components/routine-day-exercise-row';
import { Button } from '@/components/ui/button';

function RoutineEditor({ id }: { id: string }) {
  const router = useRouter();
  const routine = useLiveQuery(() => db.routines.get(id), [id]);
  const ejercicios = useLiveQuery(() => listRoutineExercises(id), [id]);

  if (routine === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!routine || routine.deletedAt !== null) return <p>Rutina no encontrada.</p>;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{routine.nombre}</h1>
        {routine.descripcion && <p className="text-sm text-muted-foreground">{routine.descripcion}</p>}
      </div>

      <section className="space-y-3">
        <h2 className="label-mono text-[11px] text-muted-foreground">Ejercicios</h2>
        {(ejercicios ?? []).length === 0 && (
          <p className="label-mono text-xs text-muted-foreground">Aún no hay ejercicios.</p>
        )}
        <ul className="brutal-box divide-y-2 divide-foreground">
          {(ejercicios ?? []).map((re) => (
            <RoutineDayExerciseRow key={re.id} routineExercise={re} />
          ))}
        </ul>
        <Link
          href={`/rutinas/${id}/anadir`}
          className="label-mono block border-2 border-dashed border-foreground bg-card/50 p-4 text-center text-xs text-foreground transition-colors hover:bg-card"
        >
          + Añadir ejercicio
        </Link>
      </section>

      <Button
        variant="destructive"
        className="w-full"
        onClick={async () => {
          if (window.confirm(`¿Borrar la rutina "${routine.nombre}"?`)) {
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

export default function RoutineEditorPage({ params }: { params: Promise<{ id: string }> }) {
  const [id, setId] = useState<string | null>(null);

  useEffect(() => {
    params.then(({ id }) => setId(id));
  }, [params]);

  if (id === null) return <p className="text-muted-foreground">Cargando…</p>;
  return <RoutineEditor id={id} />;
}
```

- [ ] **Step 4: Crear la página de añadir ejercicio a la rutina**

Crea `app/rutinas/[id]/anadir/page.tsx`:

```tsx
'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { listExercises } from '@/lib/repositories/exercises';
import { addExerciseToRoutine } from '@/lib/repositories/routines';
import { equipmentLabel } from '@/lib/labels';
import { Input } from '@/components/ui/input';

function AnadirEjercicio({ routineId }: { routineId: string }) {
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
      <ul className="brutal-box divide-y-2 divide-foreground">
        {filtrados.map((e) => (
          <li key={e.id}>
            <button
              className="flex w-full items-center justify-between p-3 text-left"
              onClick={async () => {
                await addExerciseToRoutine(routineId, { exerciseId: e.id });
                router.push(`/rutinas/${routineId}`);
              }}
            >
              <span className="font-medium">{e.nombre}</span>
              <span className="label-mono text-[10px] text-muted-foreground">{equipmentLabel[e.equipamiento]}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default function AnadirEjercicioPage({ params }: { params: Promise<{ id: string }> }) {
  const [routineId, setRoutineId] = useState<string | null>(null);

  useEffect(() => {
    params.then(({ id }) => setRoutineId(id));
  }, [params]);

  if (routineId === null) return <p className="text-muted-foreground">Cargando…</p>;
  return <AnadirEjercicio routineId={routineId} />;
}
```

- [ ] **Step 5: Borrar las rutas de día**

Importante: cita las rutas con corchetes (zsh las interpreta como glob).

```bash
git rm "app/rutinas/dia/[dayId]/page.tsx" "app/rutinas/dia/[dayId]/anadir/page.tsx"
```

(No hace falta borrar los directorios vacíos: una carpeta de ruta sin `page.tsx` no genera ruta en el App Router.)

- [ ] **Step 6: Reescribir el test de start-workout (falla)**

Reemplaza `components/start-workout.test.tsx` por:

```tsx
import { it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { createGym } from '@/lib/repositories/gyms';
import { createRoutine, addExerciseToRoutine } from '@/lib/repositories/routines';
import { StartWorkout } from '@/components/start-workout';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.routines.clear();
  await db.routineExercises.clear();
  await db.gyms.clear();
  push.mockClear();
});

it('elige gimnasio, empieza un entreno libre y navega a la sesión', async () => {
  const g = await createGym("Gold's");
  render(<StartWorkout />);
  await userEvent.click(screen.getByRole('button', { name: 'Empezar entreno libre' }));
  await userEvent.click(await screen.findByRole('button', { name: "Gold's" }));
  await waitFor(() => expect(push).toHaveBeenCalled());
  expect(await db.workoutSessions.count()).toBe(1);
  const sesion = (await db.workoutSessions.toArray())[0];
  expect(sesion.gymId).toBe(g.id);
});

it('empieza desde una rutina precargando sus ejercicios', async () => {
  await createGym("Gold's");
  const r = await createRoutine({ nombre: 'Full Body' });
  await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
  render(<StartWorkout />);
  await userEvent.click(screen.getByRole('button', { name: 'Empezar Full Body' }));
  await userEvent.click(await screen.findByRole('button', { name: "Gold's" }));
  await waitFor(() => expect(push).toHaveBeenCalled());
  const sesion = (await db.workoutSessions.toArray())[0];
  const les = await db.loggedExercises.where('sessionId').equals(sesion.id).toArray();
  expect(les).toHaveLength(1);
});
```

- [ ] **Step 7: Ejecutar (debe fallar)**

Run: `npm run test -- start-workout.test.tsx`
Expected: FAIL — no existe el botón "Empezar Full Body" (la home aún lista días).

- [ ] **Step 8: Reescribir `components/start-workout.tsx`**

Reemplaza el archivo por (rutinas→Empezar, sin días):

```tsx
'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { ArrowRight } from 'lucide-react';
import { useLiveQuery } from 'dexie-react-hooks';
import { listRoutines } from '@/lib/repositories/routines';
import { startSession } from '@/lib/repositories/workouts';
import { Button } from '@/components/ui/button';
import { GymPicker } from '@/components/gym-picker';

type Pendiente = { tipo: 'libre' } | { tipo: 'rutina'; routineId: string } | null;

export function StartWorkout() {
  const router = useRouter();
  const routines = useLiveQuery(() => listRoutines(), []);
  const [pendiente, setPendiente] = useState<Pendiente>(null);

  async function empezarConGym(gymId: string) {
    if (!pendiente) return;
    const s = pendiente.tipo === 'rutina'
      ? await startSession({ routineId: pendiente.routineId, gymId })
      : await startSession({ gymId });
    router.push(`/entrenar/${s.id}`);
  }

  if (pendiente) {
    return (
      <div className="space-y-4">
        <GymPicker onPick={empezarConGym} />
        <button
          className="label-mono text-[11px] text-muted-foreground underline"
          onClick={() => setPendiente(null)}
        >
          Cancelar
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-7">
      <Button
        onClick={() => setPendiente({ tipo: 'libre' })}
        size="lg"
        className="w-full font-[family-name:var(--font-display)] text-xl tracking-wide"
      >
        Empezar entreno libre
        <ArrowRight className="size-5" strokeWidth={3} />
      </Button>

      {(routines ?? []).length > 0 && (
        <section className="space-y-3">
          <h2 className="label-mono text-[11px] text-muted-foreground">Desde una rutina</h2>
          <ul className="brutal-box divide-y-2 divide-foreground">
            {(routines ?? []).map((r) => (
              <li key={r.id} className="flex items-center justify-between gap-2 px-3 py-2.5">
                <span className="font-semibold">{r.nombre}</span>
                <Button size="sm" onClick={() => setPendiente({ tipo: 'rutina', routineId: r.id })}>
                  Empezar {r.nombre}
                </Button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
```

Nota: el texto accesible del botón es `Empezar {nombre}` (coincide con el test). Es un poco verboso visualmente; si se quiere, en una iteración posterior se puede mostrar solo "Empezar" con `aria-label`, pero aquí mantenemos el texto literal para el test.

- [ ] **Step 9: Ejecutar (debe pasar)**

Run: `npm run test` (suite completa)
Expected: PASS — en particular `start-workout.test.tsx` y el test del editor de rutina.

- [ ] **Step 10: Verificar suite + tipos + lint**

Run: `npm run test && npx tsc --noEmit && npm run lint`
Expected: todo verde (las funciones de día siguen existiendo pero ya no se usan en UI).

- [ ] **Step 11: Commit**

```bash
git add app/rutinas components/start-workout.tsx components/start-workout.test.tsx
git commit -m "feat(routines): UI de rutina como lista plana de ejercicios + home rutinas->Empezar"
```

---

## Task 4: (Sustractivo) Eliminar la capa día (repo, sync, backup, tipos, Dexie v7, Drizzle)

**Files:**
- Modify: `lib/repositories/routines.ts`, `lib/repositories/workouts.ts`, `lib/repositories/backup.ts`,
  `lib/sync/collect.ts`, `lib/sync/apply.ts`, `lib/sync/server-tables.ts`,
  `lib/db/types.ts`, `lib/db/database.ts`, `db/schema.ts`
- Test: `lib/repositories/routines.test.ts`, `lib/repositories/workouts.test.ts`,
  `lib/repositories/backup.test.ts`, `lib/db/database.test.ts`, `components/routine-day-exercise-row.test.tsx`

- [ ] **Step 1: Quitar las funciones de día del repo**

En `lib/repositories/routines.ts`:
- Borra `addDay`, `listDays`, `updateDay`, `softDeleteDay`, `addExerciseToDay`, `listDayExercises`.
- Cambia el import de tipos a `import type { Routine, RoutineExercise } from '@/lib/db/types';` (sin `RoutineDay`).
- Reescribe `softDeleteRoutine` para cascada rutina→ejercicios por `routineId`:

```ts
export async function softDeleteRoutine(id: string): Promise<void> {
  const ts = now();
  await db.transaction('rw', db.routines, db.routineExercises, async () => {
    await db.routines.update(id, { deletedAt: ts, updatedAt: ts });
    const res = activo(await db.routineExercises.where('routineId').equals(id).toArray());
    for (const re of res) await db.routineExercises.update(re.id, { deletedAt: ts, updatedAt: ts });
  });
}
```

- [ ] **Step 2: Quitar el camino de día en `startSession`**

En `lib/repositories/workouts.ts`:
- Cambia el import a `import { listRoutineExercises } from '@/lib/repositories/routines';` (sin `listDayExercises`).
- Simplifica `startSession` (sin `routineDayId`):

```ts
export async function startSession(input: { routineId?: string; gymId?: string | null }): Promise<WorkoutSession> {
  const ts = now();
  const session: WorkoutSession = {
    id: crypto.randomUUID(),
    userId: null,
    gymId: input.gymId ?? null,
    fecha: ts,
    updatedAt: ts,
    deletedAt: null,
  };
  await db.workoutSessions.put(session);
  if (input.routineId) {
    const res = await listRoutineExercises(input.routineId);
    for (const re of res) await addLoggedExercise(session.id, re.exerciseId);
  }
  return session;
}
```

- [ ] **Step 3: Quitar `routineDays` del sync y del backup**

En `lib/sync/collect.ts`: borra la línea `{ name: 'routineDays', table: asSync(db.routineDays) },`.
En `lib/sync/apply.ts`: borra la línea `routineDays: asSync(db.routineDays),`.
En `lib/sync/server-tables.ts`: borra la línea `routineDays: schema.routineDays,`.

En `lib/repositories/backup.ts`:
- Quita `RoutineDay` del import de tipos y `routineDays: RoutineDay[];` de `BackupFile.data`.
- Sube `version: 4` → `version: 5`.
- Quita `routineDays: await db.routineDays.toArray(),` de `exportData`.
- En `importData`: quita `db.routineDays` del array `tables` y la línea `if (d.routineDays?.length) await db.routineDays.bulkPut(d.routineDays);`.

- [ ] **Step 4: Quitar el tipo `RoutineDay` y la columna legacy del RoutineExercise**

En `lib/db/types.ts`:
- Borra la interface `RoutineDay`.
- En `RoutineExercise`, quita la línea `routineDayId?: string;` (ya no se usa). `routineId: string` se queda.
- `WorkoutSession.routineDayId?: string` se MANTIENE (legacy).

- [ ] **Step 5: Dexie v7 — eliminar la tabla `routineDays`**

En `lib/db/database.ts`:
- En la cabecera de la clase, borra la línea `routineDays!: Table<RoutineDay, string>;` y quita `RoutineDay` del import de tipos.
- Añade la versión 7 al final del constructor:

```ts
    this.version(7).stores({
      routineDays: null, // eliminar la tabla (datos ya migrados a routineExercises.routineId en v6)
      routineExercises: 'id, routineId, exerciseId, orden, deletedAt',
    });
```

(Dexie aplica solo el diff: elimina `routineDays` y reindexa `routineExercises` quitando `routineDayId`; el resto de tablas se heredan de v6.)

- [ ] **Step 6: Drizzle — quitar `routine_days` y `routine_day_id`, añadir `routine_id`**

En `db/schema.ts`:
- En `routineExercises`: sustituye `routineDayId: text('routine_day_id').notNull(),` por `routineId: text('routine_id'),` (nullable en servidor).
- Borra por completo la tabla `routineDays` (`export const routineDays = pgTable('routine_days', {...});`).
- `workoutSessions.routineDayId` (la columna `routine_day_id` de sesiones) se MANTIENE (legacy).

- [ ] **Step 7: Actualizar los tests que usaban la capa día**

`lib/repositories/routines.test.ts`: reemplaza TODO el archivo por:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import {
  createRoutine, listRoutines, getRoutine, updateRoutine, softDeleteRoutine,
  addExerciseToRoutine, listRoutineExercises, updateRoutineExercise, softDeleteRoutineExercise,
} from '@/lib/repositories/routines';

beforeEach(async () => {
  await db.routines.clear();
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

  it('borra en cascada la rutina y sus ejercicios', async () => {
    const r = await createRoutine({ nombre: 'PPL' });
    const re = await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca', seriesObjetivo: 3, repsObjetivo: 8 });
    await softDeleteRoutine(r.id);
    expect(await listRoutines()).toHaveLength(0);
    expect(await listRoutineExercises(r.id)).toHaveLength(0);
    expect((await getRoutine(r.id))!.deletedAt).not.toBeNull();
    expect((await db.routineExercises.get(re.id))!.deletedAt).not.toBeNull();
  });
});

describe('ejercicios de la rutina', () => {
  it('añade con orden incremental y lista por orden', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const e1 = await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
    const e2 = await addExerciseToRoutine(r.id, { exerciseId: 'seed-sentadilla' });
    expect(e1.routineId).toBe(r.id);
    expect(e1.orden).toBe(0);
    expect(e2.orden).toBe(1);
    expect((await listRoutineExercises(r.id)).map((re) => re.exerciseId))
      .toEqual(['seed-press-banca', 'seed-sentadilla']);
  });

  it('actualiza objetivos y borra', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const re = await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
    await updateRoutineExercise(re.id, { seriesObjetivo: 4, repsObjetivo: 10, descansoSegundos: 120 });
    expect(await db.routineExercises.get(re.id)).toMatchObject({ seriesObjetivo: 4, repsObjetivo: 10, descansoSegundos: 120 });
    await softDeleteRoutineExercise(re.id);
    expect(await listRoutineExercises(r.id)).toHaveLength(0);
  });
});
```

`lib/repositories/workouts.test.ts`:
- Cambia el import de rutinas a `import { createRoutine, addExerciseToRoutine } from '@/lib/repositories/routines';`.
- En el `beforeEach`, borra la línea `await db.routineDays.clear();`.
- Borra el test antiguo `'empieza desde un día de rutina y precarga sus ejercicios en orden'` (el que usa `addDay`/`addExerciseToDay`/`startSession({ routineDayId: d.id })`). El test nuevo equivalente (`'empieza desde una rutina y precarga...'`) ya se añadió en la Task 2.
- El test `'empieza un entreno libre vacío'` que comprueba `expect(s.routineDayId).toBeUndefined()` se mantiene válido (la sesión libre no fija `routineDayId`).

`lib/repositories/backup.test.ts`:
- En ambos `beforeEach`/`Promise.all`, borra `db.routineDays.clear()`.
- Cambia el import a `import { createRoutine, addExerciseToRoutine } from '@/lib/repositories/routines';`.
- En el test `'exporta e importa todos los datos (roundtrip)'`: sustituye `await addDay(r.id, { nombre: 'Día 1' });` por `await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });`, quita `db.routineDays.clear()` del `Promise.all` intermedio, y sustituye `expect(await db.routineDays.count()).toBe(1);` por `expect(await db.routineExercises.count()).toBe(1);`.

`components/routine-day-exercise-row.test.tsx`:
- Cambia el import a `import { createRoutine, addExerciseToRoutine } from '@/lib/repositories/routines';`.
- En `beforeEach`, borra `await db.routineDays.clear();`.
- En el test, sustituye:
  ```tsx
  const d = await addDay(r.id, { nombre: 'D' });
  const re = await addExerciseToDay(d.id, { exerciseId: 'seed-press-banca' });
  ```
  por:
  ```tsx
  const re = await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
  ```

`lib/db/database.test.ts`:
- En el test `'expone las tablas de rutinas (v2)'`, borra la línea `expect(db.routineDays).toBeDefined();` (deja `routines` y `routineExercises`).

- [ ] **Step 8: Ejecutar la suite (debe pasar)**

Run: `npm run test`
Expected: toda verde. Si algún test se quejara de `db.routineDays`, queda alguna referencia por limpiar.

- [ ] **Step 9: Verificar tipos + lint**

Run: `npx tsc --noEmit && npm run lint`
Expected: limpio (no debe quedar ninguna referencia a `RoutineDay`/`routineDays`/funciones de día).

- [ ] **Step 10: Aplicar el esquema a Neon**

> Lo ejecuta el **controlador** (no un subagente) por ser DDL destructivo en producción y posibles prompts.

Run: `npm run db:push`
En los prompts de drizzle-kit: para `routine_exercises`, elegir **crear `routine_id` + dropear `routine_day_id`** (NO "rename"); confirmar **drop de la tabla `routine_days`**.
Expected: `Changes applied`.

- [ ] **Step 11: Commit**

```bash
git add lib db/schema.ts components/routine-day-exercise-row.test.tsx
git commit -m "refactor(routines): eliminar la capa día (repo, sync, backup, Dexie v7, Drizzle)"
```

---

## Task 5: Verificación final

**Files:** ninguno (solo verificación).

- [ ] **Step 1: Suite completa**

Run: `npm run test`
Expected: todos los tests verdes.

- [ ] **Step 2: Tipos**

Run: `npx tsc --noEmit`
Expected: sin errores. (No debe quedar referencia a `RoutineDay`, `routineDays`, `routineDayId` salvo `WorkoutSession.routineDayId` legacy.)

- [ ] **Step 3: Lint**

Run: `npm run lint`
Expected: sin errores.

- [ ] **Step 4: Build de producción**

Run: `npm run build`
Expected: `Compiled successfully`, service worker de Serwist generado.

- [ ] **Step 5: Comprobar que no quedan rutas de día**

Run: `ls app/rutinas/dia 2>/dev/null || echo "ok: carpeta dia eliminada"`
Expected: `ok: carpeta dia eliminada`.

- [ ] **Step 6: Commit final (si quedara algo sin commitear)**

```bash
git add -A
git commit -m "chore: verificación final rutina lista plana" || echo "nada que commitear"
```

---

## Notas de cierre

- Tras el plan: redeploy con `vercel --prod` para reflejar el cambio en `gym.chrisgoac.dev` (el `db:push` de la Task 4 ya dejó la BD lista; la app desplegada anterior seguía OK porque no leía `routine_days` de forma incompatible).
- `WorkoutSession.routineDayId` queda como campo legacy nullable sin uso (decisión del spec; evitar una segunda migración de `workout_sessions`).
- Estética Brutalist Iron mantenida en las páginas de rutina reescritas.
