# Sugerencia de peso + repes/descanso visibles al entrenar — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mostrar al entrenar, por ejercicio, una línea "Última vez" (peso × reps · hace N días, del historial del mismo gimnasio), una línea "Objetivo" (de la rutina) y un cronómetro de descanso que arranca al añadir serie.

**Architecture:** Todo se deriva de datos existentes (series registradas + objetivos de la rutina). Dos funciones de repositorio nuevas, un helper de formato puro y un componente cliente `RestTimer`; se cablean dentro de `LoggedExerciseCard`. No se toca el esquema Dexie/Drizzle, ni sync, ni backup.

**Tech Stack:** Next.js 16 (App Router, client components), Dexie/IndexedDB, dexie-react-hooks (`useLiveQuery`), Vitest + Testing Library (jsdom), Tailwind (estética "Brutalist Iron").

---

## Estructura de ficheros

- `lib/fecha.ts` — añadir `formatSegundos(s)` (puro, `mm:ss`). Test: `lib/fecha.test.ts`.
- `lib/repositories/workouts.ts` — extraer helper interno compartido y añadir `getLastPerformance`. Test: `lib/repositories/workouts.test.ts`.
- `lib/repositories/routines.ts` — añadir `getRoutineExerciseTarget`. Test: `lib/repositories/routines.test.ts`.
- `components/rest-timer.tsx` — componente nuevo. Test: `components/rest-timer.test.tsx`.
- `components/logged-exercise-card.tsx` — bloque de referencia + cronómetro + prop `routineId`. Test: `components/logged-exercise-card.test.tsx`.
- `app/entrenar/[sessionId]/page.tsx` — pasar `routineId` a la card.

---

## Task 1: `formatSegundos` (helper de formato mm:ss)

**Files:**
- Modify: `lib/fecha.ts`
- Test: `lib/fecha.test.ts`

- [ ] **Step 1: Escribir el test que falla**

Añadir al final de `lib/fecha.test.ts`:

```ts
import { formatSegundos } from '@/lib/fecha';

describe('formatSegundos', () => {
  it('formatea segundos como mm:ss', () => {
    expect(formatSegundos(0)).toBe('0:00');
    expect(formatSegundos(5)).toBe('0:05');
    expect(formatSegundos(65)).toBe('1:05');
    expect(formatSegundos(600)).toBe('10:00');
  });
  it('nunca devuelve negativos', () => {
    expect(formatSegundos(-3)).toBe('0:00');
  });
});
```

(Mantén el `import { formatHaceDias } from '@/lib/fecha';` existente arriba; añade `formatSegundos` a ese import o usa el import nuevo mostrado.)

- [ ] **Step 2: Ejecutar el test y verificar que falla**

Run: `npm test -- fecha`
Expected: FAIL — `formatSegundos is not a function` / export no encontrado.

- [ ] **Step 3: Implementar**

Añadir a `lib/fecha.ts`:

```ts
/** Segundos → "m:ss" (p. ej. 65 → "1:05"). Negativos se tratan como 0. */
export function formatSegundos(s: number): string {
  const total = Math.max(0, Math.floor(s));
  const m = Math.floor(total / 60);
  const seg = total % 60;
  return `${m}:${String(seg).padStart(2, '0')}`;
}
```

- [ ] **Step 4: Ejecutar el test y verificar que pasa**

Run: `npm test -- fecha`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lib/fecha.ts lib/fecha.test.ts
git commit -m "feat(fecha): formatSegundos para mostrar mm:ss"
```

---

## Task 2: `getLastPerformance` (peso/reps/fecha del último set, mismo gym)

**Files:**
- Modify: `lib/repositories/workouts.ts` (refactor de `getLastSet` + función nueva)
- Test: `lib/repositories/workouts.test.ts`

- [ ] **Step 1: Escribir el test que falla**

En `lib/repositories/workouts.test.ts`, añadir `getLastPerformance` al import de `@/lib/repositories/workouts` y añadir este bloque al final del fichero (antes del último `});` de cierre del `describe`, o como `describe` nuevo independiente):

```ts
describe('getLastPerformance', () => {
  it('devuelve peso, reps y fecha del último set del mismo ejercicio', async () => {
    const vieja = await startSession({});
    const le = await addLoggedExercise(vieja.id, 'seed-sentadilla');
    await addSet(le.id, { peso: 80, reps: 5 });
    await addSet(le.id, { peso: 85, reps: 6 });
    await new Promise((res) => setTimeout(res, 3));
    const nueva = await startSession({});
    const perf = await getLastPerformance('seed-sentadilla', nueva.id);
    expect(perf).toMatchObject({ peso: 85, reps: 6, fecha: vieja.fecha });
  });

  it('filtra por gimnasio igual que getLastSet', async () => {
    const a = await startSession({ gymId: 'gymA' });
    const leA = await addLoggedExercise(a.id, 'seed-sentadilla');
    await addSet(leA.id, { peso: 100, reps: 5 });
    await new Promise((res) => setTimeout(res, 3));
    const b = await startSession({ gymId: 'gymB' });
    const leB = await addLoggedExercise(b.id, 'seed-sentadilla');
    await addSet(leB.id, { peso: 80, reps: 5 });
    await new Promise((res) => setTimeout(res, 3));
    const nueva = await startSession({ gymId: 'gymA' });
    expect(await getLastPerformance('seed-sentadilla', nueva.id, 'gymA')).toMatchObject({ peso: 100 });
  });

  it('undefined si no hay histórico (excluyendo la sesión actual)', async () => {
    const s = await startSession({});
    const le = await addLoggedExercise(s.id, 'seed-sentadilla');
    await addSet(le.id, { peso: 100, reps: 3 });
    expect(await getLastPerformance('seed-sentadilla', s.id)).toBeUndefined();
  });
});
```

- [ ] **Step 2: Ejecutar el test y verificar que falla**

Run: `npm test -- workouts`
Expected: FAIL — `getLastPerformance is not a function`.

- [ ] **Step 3: Refactor + implementación mínima**

En `lib/repositories/workouts.ts`, **reemplazar** la función `getLastSet` actual por un helper interno compartido más `getLastSet` y `getLastPerformance`:

```ts
/** Busca el último set (y la fecha de su sesión) del ejercicio, filtrando por gym. */
async function findLastSetWithFecha(
  exerciseId: string,
  excludeSessionId?: string,
  gymId?: string | null,
): Promise<{ set: LoggedSet; fecha: number } | undefined> {
  const les = activo(await db.loggedExercises.where('exerciseId').equals(exerciseId).toArray())
    .filter((le) => le.sessionId !== excludeSessionId);
  if (les.length === 0) return undefined;
  const sessionIds = [...new Set(les.map((le) => le.sessionId))];
  const sessions = await db.workoutSessions.bulkGet(sessionIds);
  const fechaBySession = new Map<string, number>();
  const gymBySession = new Map<string, string | null | undefined>();
  for (const s of sessions) if (s) {
    fechaBySession.set(s.id, s.fecha);
    gymBySession.set(s.id, s.gymId ?? null);
  }
  const candidatos = gymId == null
    ? les
    : les.filter((le) => gymBySession.get(le.sessionId) === gymId);
  candidatos.sort((a, b) => (fechaBySession.get(b.sessionId) ?? 0) - (fechaBySession.get(a.sessionId) ?? 0));
  for (const le of candidatos) {
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    if (sets.length > 0) {
      sets.sort((a, b) => b.orden - a.orden);
      return { set: sets[0], fecha: fechaBySession.get(le.sessionId) ?? 0 };
    }
  }
  return undefined;
}

export async function getLastSet(
  exerciseId: string,
  excludeSessionId?: string,
  gymId?: string | null,
): Promise<LoggedSet | undefined> {
  return (await findLastSetWithFecha(exerciseId, excludeSessionId, gymId))?.set;
}

export async function getLastPerformance(
  exerciseId: string,
  excludeSessionId?: string,
  gymId?: string | null,
): Promise<{ peso: number; reps: number; fecha: number } | undefined> {
  const found = await findLastSetWithFecha(exerciseId, excludeSessionId, gymId);
  if (!found) return undefined;
  return { peso: found.set.peso, reps: found.set.reps, fecha: found.fecha };
}
```

- [ ] **Step 4: Ejecutar los tests y verificar que pasan (incluidos los antiguos de getLastSet)**

Run: `npm test -- workouts`
Expected: PASS — los tests existentes de `getLastSet` y los nuevos de `getLastPerformance` en verde.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/workouts.ts lib/repositories/workouts.test.ts
git commit -m "feat(workouts): getLastPerformance (peso/reps/fecha del último set por gym)"
```

---

## Task 3: `getRoutineExerciseTarget` (objetivos de la rutina)

**Files:**
- Modify: `lib/repositories/routines.ts`
- Test: `lib/repositories/routines.test.ts`

- [ ] **Step 1: Escribir el test que falla**

En `lib/repositories/routines.test.ts`, añadir `getRoutineExerciseTarget` al import de `@/lib/repositories/routines` y añadir:

```ts
describe('getRoutineExerciseTarget', () => {
  it('devuelve los objetivos del ejercicio en la rutina', async () => {
    const r = await createRoutine({ nombre: 'R' });
    await addExerciseToRoutine(r.id, {
      exerciseId: 'seed-press-banca', seriesObjetivo: 4, repsObjetivo: 10, descansoSegundos: 90,
    });
    const t = await getRoutineExerciseTarget(r.id, 'seed-press-banca');
    expect(t).toMatchObject({ seriesObjetivo: 4, repsObjetivo: 10, descansoSegundos: 90 });
  });

  it('undefined si el ejercicio no está en la rutina', async () => {
    const r = await createRoutine({ nombre: 'R' });
    expect(await getRoutineExerciseTarget(r.id, 'seed-press-banca')).toBeUndefined();
  });
});
```

Asegúrate de que `createRoutine` y `addExerciseToRoutine` ya están importados en ese fichero (si no, añádelos al import existente).

- [ ] **Step 2: Ejecutar el test y verificar que falla**

Run: `npm test -- routines`
Expected: FAIL — `getRoutineExerciseTarget is not a function`.

- [ ] **Step 3: Implementar**

Añadir a `lib/repositories/routines.ts` (junto a `listRoutineExercises`):

```ts
/** Objetivos (series/reps/descanso) de un ejercicio dentro de una rutina; undefined si no está. */
export async function getRoutineExerciseTarget(
  routineId: string,
  exerciseId: string,
): Promise<RoutineExercise | undefined> {
  const res = await listRoutineExercises(routineId);
  return res.find((re) => re.exerciseId === exerciseId);
}
```

(`RoutineExercise` ya está importado en este fichero; si no lo estuviera, añádelo a su import de `@/lib/db/types`.)

- [ ] **Step 4: Ejecutar el test y verificar que pasa**

Run: `npm test -- routines`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/routines.ts lib/repositories/routines.test.ts
git commit -m "feat(routines): getRoutineExerciseTarget para leer objetivos al entrenar"
```

---

## Task 4: Componente `RestTimer`

**Files:**
- Create: `components/rest-timer.tsx`
- Test: `components/rest-timer.test.tsx`

El cronómetro arranca cuando cambia la prop `startKey` (la card la incrementa al "Añadir serie"). `startKey === 0` significa "aún no arrancado" → no se muestra nada. Cuenta atrás desde `targetSeconds` (o hacia arriba si no hay). Tocar el cronómetro lo para.

- [ ] **Step 1: Escribir el test que falla**

Crear `components/rest-timer.test.tsx`:

```tsx
import { it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { RestTimer } from '@/components/rest-timer';

it('no muestra nada antes de arrancar (startKey 0)', () => {
  const { container } = render(<RestTimer startKey={0} targetSeconds={90} />);
  expect(container).toBeEmptyDOMElement();
});

it('muestra el tiempo objetivo al arrancar', () => {
  render(<RestTimer startKey={1} targetSeconds={90} />);
  expect(screen.getByText('1:30')).toBeInTheDocument();
});

it('arranca en 0:00 cuando no hay objetivo (cuenta arriba)', () => {
  render(<RestTimer startKey={1} />);
  expect(screen.getByText('0:00')).toBeInTheDocument();
});
```

- [ ] **Step 2: Ejecutar el test y verificar que falla**

Run: `npm test -- rest-timer`
Expected: FAIL — no existe `@/components/rest-timer`.

- [ ] **Step 3: Implementar**

Crear `components/rest-timer.tsx`. **Importante (reglas de hooks):** TODOS los hooks (`useState`, los tres `useEffect`) van **antes** del único `return` condicional (`if (startKey === 0) return null;`). El efecto de vibración recalcula "terminado" dentro de sí mismo para no depender de variables declaradas tras el early-return.

```tsx
'use client';

import { useEffect, useState } from 'react';
import { formatSegundos } from '@/lib/fecha';

/**
 * Cronómetro de descanso. Arranca cada vez que cambia `startKey` (>0).
 * Con `targetSeconds`: cuenta atrás y avisa (parpadeo + vibración) al llegar a 0.
 * Sin `targetSeconds`: cuenta hacia arriba como cronómetro libre.
 * Tocarlo lo detiene.
 */
export function RestTimer({ startKey, targetSeconds }: { startKey: number; targetSeconds?: number }) {
  const [activo, setActivo] = useState(false);
  const [transcurridos, setTranscurridos] = useState(0);

  // (Re)arranca cuando cambia startKey.
  useEffect(() => {
    if (startKey > 0) {
      setActivo(true);
      setTranscurridos(0);
    }
  }, [startKey]);

  // Tic de 1s mientras está activo.
  useEffect(() => {
    if (!activo) return;
    const id = setInterval(() => setTranscurridos((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [activo]);

  // Vibra al llegar a 0 (solo cuenta atrás). Recalcula "terminado" aquí dentro.
  useEffect(() => {
    const tieneObj = typeof targetSeconds === 'number' && targetSeconds > 0;
    if (tieneObj && targetSeconds - transcurridos <= 0
        && typeof navigator !== 'undefined' && 'vibrate' in navigator) {
      navigator.vibrate?.(200);
    }
  }, [transcurridos, targetSeconds]);

  if (startKey === 0) return null;

  const tieneObjetivo = typeof targetSeconds === 'number' && targetSeconds > 0;
  const restante = tieneObjetivo ? targetSeconds - transcurridos : 0;
  const terminado = tieneObjetivo && restante <= 0;
  const display = tieneObjetivo ? formatSegundos(Math.max(0, restante)) : formatSegundos(transcurridos);

  return (
    <button
      type="button"
      onClick={() => setActivo(false)}
      className={`label-mono flex w-full items-center justify-center gap-2 border-2 border-foreground px-3 py-2 text-sm tabular-nums ${
        terminado ? 'animate-pulse bg-primary text-primary-foreground' : 'bg-card text-foreground'
      }`}
      aria-label="Descanso"
    >
      <span className="text-[10px] opacity-70">DESCANSO</span>
      <span className="font-[family-name:var(--font-display)] text-lg leading-none">{display}</span>
      {!activo && <span className="text-[10px] opacity-70">(parado)</span>}
    </button>
  );
}
```

- [ ] **Step 4: Ejecutar el test y verificar que pasa**

Run: `npm test -- rest-timer`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add components/rest-timer.tsx components/rest-timer.test.tsx
git commit -m "feat(entreno): componente RestTimer (cuenta atrás/arriba de descanso)"
```

---

## Task 5: Cablear en `LoggedExerciseCard` + página

**Files:**
- Modify: `components/logged-exercise-card.tsx`
- Modify: `app/entrenar/[sessionId]/page.tsx`
- Test: `components/logged-exercise-card.test.tsx`

- [ ] **Step 1: Escribir el test que falla**

Añadir a `components/logged-exercise-card.test.tsx` un test del bloque "Última vez". Necesita una sesión previa con una serie en el mismo (sin) gym:

```tsx
import { addSet } from '@/lib/repositories/workouts';

it('muestra "Última vez" con el peso y reps del entreno anterior', async () => {
  const vieja = await startSession({});
  const leVieja = await addLoggedExercise(vieja.id, 'seed-press-banca');
  await addSet(leVieja.id, { peso: 70, reps: 8 });
  await new Promise((res) => setTimeout(res, 3));

  const s = await startSession({});
  const le = await addLoggedExercise(s.id, 'seed-press-banca');
  render(<LoggedExerciseCard loggedExercise={le} sessionId={s.id} />);

  expect(await screen.findByText(/ÚLTIMA VEZ/i)).toBeInTheDocument();
  expect(await screen.findByText(/70/)).toBeInTheDocument();
});
```

(Reusa los imports/`beforeEach` existentes del fichero; añade `addSet` al import de `@/lib/repositories/workouts`.)

- [ ] **Step 2: Ejecutar el test y verificar que falla**

Run: `npm test -- logged-exercise-card`
Expected: FAIL — no aparece "ÚLTIMA VEZ".

- [ ] **Step 3: Implementar en `logged-exercise-card.tsx`**

3a. Ampliar imports y props:

```tsx
import { useState } from 'react';
import {
  addSet, updateSet, softDeleteSet, listExerciseSets, getLastSet, getLastPerformance,
  softDeleteLoggedExercise,
} from '@/lib/repositories/workouts';
import { getRoutineExerciseTarget } from '@/lib/repositories/routines';
import { formatHaceDias } from '@/lib/fecha';
import { RestTimer } from '@/components/rest-timer';
```

Cambiar la firma del componente para aceptar `routineId`:

```tsx
export function LoggedExerciseCard({
  loggedExercise,
  sessionId,
  gymId,
  routineId,
}: {
  loggedExercise: LoggedExercise;
  sessionId: string;
  gymId?: string;
  routineId?: string;
}) {
```

3b. Añadir queries y estado del cronómetro (junto a los `useLiveQuery` existentes):

```tsx
  const ultima = useLiveQuery(
    () => getLastPerformance(loggedExercise.exerciseId, sessionId, gymId),
    [loggedExercise.exerciseId, sessionId, gymId],
  );
  const objetivo = useLiveQuery(
    () => (routineId ? getRoutineExerciseTarget(routineId, loggedExercise.exerciseId) : undefined),
    [routineId, loggedExercise.exerciseId],
  );
  const [restKey, setRestKey] = useState(0);
```

3c. Arrancar el cronómetro al añadir serie — en `añadirSerie`, añade al final (tras los `addSet`):

```tsx
  async function añadirSerie() {
    const actuales = sets ?? [];
    if (actuales.length > 0) {
      const ultimaSerie = actuales[actuales.length - 1];
      await addSet(loggedExercise.id, { peso: ultimaSerie.peso, reps: ultimaSerie.reps });
    } else {
      const previa = await getLastSet(loggedExercise.exerciseId, sessionId, gymId);
      await addSet(loggedExercise.id, { peso: previa?.peso ?? 0, reps: previa?.reps ?? 0 });
    }
    setRestKey((k) => k + 1);
  }
```

3d. Renderizar el bloque de referencia justo después del `<div className="flex items-center justify-between ...">` de la cabecera (antes del `<ul>` de series):

```tsx
      {(ultima || objetivo) && (
        <div className="space-y-0.5 border-b-2 border-foreground bg-card px-3 py-2">
          {ultima && (
            <p className="label-mono text-[10px] text-muted-foreground">
              ÚLTIMA VEZ · {ultima.peso} × {ultima.reps} · {formatHaceDias(ultima.fecha)}
            </p>
          )}
          {objetivo && (objetivo.seriesObjetivo || objetivo.repsObjetivo || objetivo.descansoSegundos) && (
            <p className="label-mono text-[10px] text-muted-foreground">
              OBJETIVO
              {objetivo.seriesObjetivo || objetivo.repsObjetivo
                ? ` · ${objetivo.seriesObjetivo ?? '—'} × ${objetivo.repsObjetivo ?? '—'}`
                : ''}
              {objetivo.descansoSegundos ? ` · desc. ${objetivo.descansoSegundos}s` : ''}
            </p>
          )}
        </div>
      )}
```

3e. Renderizar el cronómetro dentro del bloque inferior, encima del botón "Añadir serie":

```tsx
      <div className="space-y-2 border-t-2 border-foreground p-3">
        <RestTimer startKey={restKey} targetSeconds={objetivo?.descansoSegundos} />
        <Button type="button" variant="outline" className="w-full" onClick={añadirSerie}>
          Añadir serie
        </Button>
      </div>
```

(Sustituye el `<div className="border-t-2 border-foreground p-3">` existente que envuelve el botón por el de arriba.)

- [ ] **Step 4: Pasar `routineId` desde la página**

En `app/entrenar/[sessionId]/page.tsx`, en el `.map` de ejercicios:

```tsx
        {(ejercicios ?? []).map((le) => (
          <LoggedExerciseCard
            key={le.id}
            loggedExercise={le}
            sessionId={sessionId}
            gymId={session.gymId ?? undefined}
            routineId={session.routineId ?? undefined}
          />
        ))}
```

- [ ] **Step 5: Ejecutar los tests y verificar que pasan**

Run: `npm test -- logged-exercise-card`
Expected: PASS — el test nuevo de "Última vez" y el test existente de añadir serie en verde.

- [ ] **Step 6: Commit**

```bash
git add components/logged-exercise-card.tsx app/entrenar/[sessionId]/page.tsx components/logged-exercise-card.test.tsx
git commit -m "feat(entreno): bloque Última vez/Objetivo + cronómetro de descanso en la card"
```

---

## Task 6: Verificación completa

**Files:** ninguno (solo verificación)

- [ ] **Step 1: Suite completa de tests**

Run: `npm test`
Expected: PASS — todos los tests (los ~159 previos + los nuevos de fecha/workouts/routines/rest-timer/logged-exercise-card).

- [ ] **Step 2: Tipos**

Run: `npx tsc --noEmit`
Expected: sin errores.

- [ ] **Step 3: Lint**

Run: `npm run lint`
Expected: sin errores.

- [ ] **Step 4: Build**

Run: `npm run build`
Expected: build OK (usa `--webpack`, ya configurado en el script).

- [ ] **Step 5: Commit final (si hubo ajustes de tsc/lint)**

```bash
git add -A
git commit -m "chore(entreno): verificación tsc/lint/build de sugerencia de peso + descanso"
```

---

## Verificación contra el spec

- "Sugerencia de peso visible, mismo gym" → Task 2 (`getLastPerformance`, filtra por gym) + Task 5 (línea "ÚLTIMA VEZ").
- "Ver repes" → línea "ÚLTIMA VEZ" (reps reales) + línea "OBJETIVO" (reps de rutina), Task 5.
- "Mostrar descanso objetivo" → línea "OBJETIVO · desc. Ns", Task 5 (datos de Task 3).
- "Cronómetro que arranca tras cada serie" → Task 4 (`RestTimer`) + Task 5 (`setRestKey` en `añadirSerie`).
- "No tocar Dexie/Drizzle/sync/backup" → ninguna task modifica esquema ni capa de sync.
- Casos límite (sin historial / entreno libre / sin objetivo / sin vibrate) → cubiertos por renders condicionales (Task 5) y feature-detection (Task 4).
