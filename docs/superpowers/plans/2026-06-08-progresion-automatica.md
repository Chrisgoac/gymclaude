# Progresión automática (A) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que la app sugiera el próximo objetivo (subir peso o reps) al entrenar desde una rutina, según un modo de progresión configurable y un salto de peso inferido del historial.

**Architecture:** Un módulo puro `lib/progresion.ts` con dos funciones (`inferirSalto`, `calcularSugerencia`) que no tocan la DB y son 100% testeables. Los ajustes globales (modo + incrementos por equipamiento) viven en `localStorage` siguiendo el patrón de `lib/settings.ts`. El override por ejercicio (`incrementoKg`) y el rango (`repsObjetivoMin`) son campos opcionales no indexados en Dexie (sin migración). `LoggedExerciseCard` consume el módulo puro para autorrellenar la primera serie y mostrar un badge explicativo.

**Tech Stack:** Next.js 16 (App Router) · TypeScript · Dexie (IndexedDB) · Vitest + Testing Library + fake-indexeddb · Tailwind v4 (sistema "Brutalist Iron").

**Nota de spec:** El spec describía los ajustes globales como "sincronizables (syncState)". El patrón real del repo es `localStorage` (ver `lib/settings.ts`, no sincronizado). Este plan sigue el patrón real: modo e incrementos son locales por dispositivo. El override por ejercicio y `repsObjetivoMin` SÍ se sincronizan (van en los registros `Exercise`/`RoutineExercise`).

---

## File Structure

- **Create** `lib/progresion.ts` — módulo puro: tipos `ModoProgresion`/`Motivo`/`Sugerencia`, constantes `INCREMENTO_DEFAULTS`/`SANE_STEPS`, funciones `inferirSalto` y `calcularSugerencia` y formateador `describeMotivo`.
- **Create** `lib/progresion.test.ts` — tests del módulo puro.
- **Modify** `lib/db/types.ts` — añadir `RoutineExercise.repsObjetivoMin?` y `Exercise.incrementoKg?`.
- **Modify** `lib/repositories/routines.ts` — `updateRoutineExercise` acepta `repsObjetivoMin`.
- **Modify** `lib/repositories/exercises.ts` — `NewExerciseInput`/`ExerciseChanges` aceptan `incrementoKg`.
- **Modify** `lib/repositories/workouts.ts` — nueva `getLastWorkingSets` (series de trabajo de la última sesión, mismo gym).
- **Modify** `lib/repositories/workouts.test.ts` — test de `getLastWorkingSets`.
- **Modify** `lib/settings.ts` — modo de progresión + incrementos por equipamiento (localStorage).
- **Modify** `lib/settings.test.ts` — tests de los nuevos ajustes.
- **Modify** `components/logged-exercise-card.tsx` — autorrelleno con la sugerencia + badge.
- **Modify** `components/logged-exercise-card.test.tsx` — test del autorrelleno y el badge.
- **Modify** `components/routine-day-exercise-row.tsx` — campo "Reps mín" (rango).
- **Modify** `components/exercise-form.tsx` — campo "Incremento (kg)" override.
- **Modify** `app/ajustes/page.tsx` — sección "Progresión" (modo + tabla de incrementos).

---

## Task 1: Campos de datos (`repsObjetivoMin`, `incrementoKg`)

**Files:**
- Modify: `lib/db/types.ts:57-65` (RoutineExercise), `lib/db/types.ts:17-29` (Exercise)
- Modify: `lib/repositories/routines.ts:100-105`
- Modify: `lib/repositories/exercises.ts:4-9`
- Test: `lib/repositories/routines.test.ts`

- [ ] **Step 1: Escribir el test que falla** (persistencia de los campos)

En `lib/repositories/routines.test.ts`, añade dentro del `describe` existente de routine-exercises (o crea uno nuevo al final del fichero):

```ts
it('updateRoutineExercise persiste repsObjetivoMin', async () => {
  const r = await createRoutine({ nombre: 'R' });
  const re = await addRoutineExercise(r.id, 'ex-1');
  await updateRoutineExercise(re.id, { repsObjetivo: 12, repsObjetivoMin: 8 });
  const leido = (await listRoutineExercises(r.id))[0];
  expect(leido.repsObjetivoMin).toBe(8);
  expect(leido.repsObjetivo).toBe(12);
});
```

Asegúrate de que los imports al inicio del test incluyan `createRoutine, addRoutineExercise, updateRoutineExercise, listRoutineExercises` (copia los que ya use el fichero; añade los que falten).

- [ ] **Step 2: Ejecutar el test y verlo fallar**

Run: `npx vitest run lib/repositories/routines.test.ts -t "repsObjetivoMin"`
Expected: FAIL — TypeScript error "repsObjetivoMin does not exist in type" o el assert `toBe(8)` falla (queda `undefined`).

- [ ] **Step 3: Añadir los campos a los tipos**

En `lib/db/types.ts`, dentro de `interface RoutineExercise`, tras `repsObjetivo?: number;`:

```ts
  /** Tope inferior del rango de reps (doble progresión). Sin valor → se asume tope−4. */
  repsObjetivoMin?: number;
```

Dentro de `interface Exercise`, tras `notas?: string;`:

```ts
  /** Override manual del salto de peso al progresar (kg). Gana sobre la inferencia. */
  incrementoKg?: number;
```

- [ ] **Step 4: Aceptar los campos en los repos**

En `lib/repositories/routines.ts`, amplía el tipo de `changes` de `updateRoutineExercise`:

```ts
export async function updateRoutineExercise(
  id: string,
  changes: Partial<Pick<RoutineExercise, 'seriesObjetivo' | 'repsObjetivo' | 'repsObjetivoMin' | 'descansoSegundos' | 'notas'>>,
): Promise<void> {
  await db.routineExercises.update(id, { ...changes, updatedAt: now() });
}
```

En `lib/repositories/exercises.ts`, amplía ambos tipos:

```ts
export type NewExerciseInput = Pick<Exercise, 'nombre' | 'grupoMuscular' | 'equipamiento' | 'tipo'> &
  Partial<Pick<Exercise, 'videoUrl' | 'notas' | 'incrementoKg'>>;

export type ExerciseChanges = Partial<
  Pick<Exercise, 'nombre' | 'grupoMuscular' | 'equipamiento' | 'tipo' | 'videoUrl' | 'notas' | 'incrementoKg'>
>;
```

- [ ] **Step 5: Ejecutar el test y verlo pasar**

Run: `npx vitest run lib/repositories/routines.test.ts -t "repsObjetivoMin"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add lib/db/types.ts lib/repositories/routines.ts lib/repositories/exercises.ts lib/repositories/routines.test.ts
git commit -m "feat(progresion): campos repsObjetivoMin (rango) e incrementoKg (override)"
```

---

## Task 2: `inferirSalto` (módulo puro)

**Files:**
- Create: `lib/progresion.ts`
- Test: `lib/progresion.test.ts`

- [ ] **Step 1: Escribir los tests que fallan**

Crea `lib/progresion.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { inferirSalto, INCREMENTO_DEFAULTS } from '@/lib/progresion';

const defaults = INCREMENTO_DEFAULTS;

describe('inferirSalto', () => {
  it('GCD de las diferencias entre pesos distintos', () => {
    expect(inferirSalto([40, 45, 50, 60], { equipamiento: 'maquina', defaults })).toBe(5);
  });
  it('soporta saltos de 2,5', () => {
    expect(inferirSalto([40, 42.5, 45], { equipamiento: 'barra', defaults })).toBe(2.5);
  });
  it('redondea ruido al valor sano más cercano', () => {
    // 40, 47.5 → diff 7.5 (ya sano)
    expect(inferirSalto([40, 47.5], { equipamiento: 'maquina', defaults })).toBe(7.5);
  });
  it('sin historial suficiente → default por equipamiento', () => {
    expect(inferirSalto([], { equipamiento: 'mancuerna', defaults })).toBe(2);
    expect(inferirSalto([40], { equipamiento: 'maquina', defaults })).toBe(5);
  });
  it('ignora pesos 0 y duplicados', () => {
    expect(inferirSalto([0, 40, 40, 45], { equipamiento: 'barra', defaults })).toBe(5);
  });
  it('el override gana sobre la inferencia', () => {
    expect(inferirSalto([40, 45, 50], { equipamiento: 'maquina', defaults, override: 1.25 })).toBe(1.25);
  });
});
```

- [ ] **Step 2: Ejecutar y verlo fallar**

Run: `npx vitest run lib/progresion.test.ts`
Expected: FAIL — "Cannot find module '@/lib/progresion'".

- [ ] **Step 3: Implementar `lib/progresion.ts` (parte 1)**

Crea `lib/progresion.ts`:

```ts
import type { Equipment } from '@/lib/db/types';

/** Salto de peso por defecto (kg) según equipamiento. Semilla cuando no hay historial. */
export const INCREMENTO_DEFAULTS: Record<Equipment, number> = {
  barra: 2.5,
  mancuerna: 2,
  maquina: 5,
  polea: 2.5,
  peso_corporal: 0,
  otro: 2.5,
};

/** Saltos plausibles a los que se redondea la inferencia para evitar ruido. */
export const SANE_STEPS = [0.5, 1, 1.25, 2, 2.5, 5, 7.5, 10];

function gcd(a: number, b: number): number {
  return b === 0 ? a : gcd(b, a % b);
}

function snapSano(v: number): number {
  return SANE_STEPS.reduce((best, s) => (Math.abs(s - v) < Math.abs(best - v) ? s : best), SANE_STEPS[0]);
}

/**
 * Deduce el salto de peso real de un ejercicio a partir de los pesos ya registrados.
 * Prioridad: override manual → inferencia (GCD de diferencias) → default por equipamiento.
 */
export function inferirSalto(
  pesos: number[],
  opts: { equipamiento: Equipment; defaults: Record<Equipment, number>; override?: number },
): number {
  if (opts.override && opts.override > 0) return opts.override;
  const distintos = [...new Set(pesos.filter((p) => p > 0))].sort((a, b) => a - b);
  if (distintos.length >= 2) {
    // Escalar ×100 para trabajar con enteros (evita errores de coma flotante con 2,5 / 1,25).
    let g = 0;
    for (let i = 1; i < distintos.length; i++) {
      g = gcd(g, Math.round((distintos[i] - distintos[i - 1]) * 100));
    }
    return snapSano(g / 100);
  }
  return opts.defaults[opts.equipamiento] ?? 2.5;
}
```

- [ ] **Step 4: Ejecutar y verlo pasar**

Run: `npx vitest run lib/progresion.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add lib/progresion.ts lib/progresion.test.ts
git commit -m "feat(progresion): inferirSalto deduce el incremento del historial"
```

---

## Task 3: `calcularSugerencia` + `describeMotivo` (módulo puro)

**Files:**
- Modify: `lib/progresion.ts`
- Modify: `lib/progresion.test.ts`

- [ ] **Step 1: Escribir los tests que fallan**

Añade a `lib/progresion.test.ts`:

```ts
import { calcularSugerencia, describeMotivo } from '@/lib/progresion';

const objetivo = { repsObjetivo: 12, repsObjetivoMin: 8 };

describe('calcularSugerencia', () => {
  it('off → repite el último peso/reps sin badge', () => {
    const s = calcularSugerencia({ modo: 'off', ultimo: [{ peso: 40, reps: 12 }], objetivo, salto: 5, esCorporal: false });
    expect(s).toEqual({ pesoSugerido: 40, repsSugeridas: 12, motivo: 'off' });
  });

  it('sin objetivo (entreno libre) → repite el último, motivo libre', () => {
    const s = calcularSugerencia({ modo: 'objetivo', ultimo: [{ peso: 30, reps: 10 }], objetivo: undefined, salto: 5, esCorporal: false });
    expect(s.pesoSugerido).toBe(30);
    expect(s.motivo).toBe('libre');
  });

  it('sin historial → peso 0, reps = objetivo, motivo sin-historial', () => {
    const s = calcularSugerencia({ modo: 'objetivo', ultimo: undefined, objetivo, salto: 5, esCorporal: false });
    expect(s).toEqual({ pesoSugerido: 0, repsSugeridas: 12, motivo: 'sin-historial' });
  });

  it('objetivo · éxito (todas las series al objetivo) → +salto', () => {
    const s = calcularSugerencia({ modo: 'objetivo', ultimo: [{ peso: 40, reps: 12 }, { peso: 40, reps: 12 }], objetivo, salto: 5, esCorporal: false });
    expect(s).toEqual({ pesoSugerido: 45, repsSugeridas: 12, motivo: 'subio-peso' });
  });

  it('objetivo · fallo parcial (una serie no llega) → repite peso', () => {
    const s = calcularSugerencia({ modo: 'objetivo', ultimo: [{ peso: 40, reps: 12 }, { peso: 40, reps: 10 }], objetivo, salto: 5, esCorporal: false });
    expect(s).toEqual({ pesoSugerido: 40, repsSugeridas: 12, motivo: 'repite' });
  });

  it('doble · éxito (todas al tope) → +salto y reps al mín del rango', () => {
    const s = calcularSugerencia({ modo: 'doble', ultimo: [{ peso: 40, reps: 12 }, { peso: 40, reps: 12 }], objetivo, salto: 2.5, esCorporal: false });
    expect(s).toEqual({ pesoSugerido: 42.5, repsSugeridas: 8, motivo: 'subio-peso' });
  });

  it('doble · sin llegar al tope → mismo peso, +1 rep hacia el tope', () => {
    const s = calcularSugerencia({ modo: 'doble', ultimo: [{ peso: 40, reps: 9 }, { peso: 40, reps: 9 }], objetivo, salto: 2.5, esCorporal: false });
    expect(s).toEqual({ pesoSugerido: 40, repsSugeridas: 10, motivo: 'subio-reps' });
  });

  it('doble · rango por defecto cuando falta repsObjetivoMin (tope−4)', () => {
    const s = calcularSugerencia({ modo: 'doble', ultimo: [{ peso: 40, reps: 12 }], objetivo: { repsObjetivo: 12 }, salto: 2.5, esCorporal: false });
    expect(s.repsSugeridas).toBe(8); // 12 − 4
  });

  it('repite · éxito → +salto', () => {
    const s = calcularSugerencia({ modo: 'repite', ultimo: [{ peso: 50, reps: 12 }], objetivo, salto: 5, esCorporal: false });
    expect(s.pesoSugerido).toBe(55);
    expect(s.motivo).toBe('subio-peso');
  });

  it('peso corporal · éxito → no toca peso, sube reps', () => {
    const s = calcularSugerencia({ modo: 'objetivo', ultimo: [{ peso: 0, reps: 12 }], objetivo, salto: 5, esCorporal: true });
    expect(s.pesoSugerido).toBe(0);
    expect(s.repsSugeridas).toBe(13);
    expect(s.motivo).toBe('subio-reps');
  });
});

describe('describeMotivo', () => {
  it('subio-peso muestra el salto', () => {
    expect(describeMotivo({ pesoSugerido: 45, repsSugeridas: 12, motivo: 'subio-peso' }, 5)).toBe('▲ +5 kg');
  });
  it('subio-reps', () => {
    expect(describeMotivo({ pesoSugerido: 40, repsSugeridas: 10, motivo: 'subio-reps' }, 2.5)).toBe('▲ +1 rep');
  });
  it('repite', () => {
    expect(describeMotivo({ pesoSugerido: 40, repsSugeridas: 12, motivo: 'repite' }, 5)).toBe('= repite');
  });
  it('sin badge para off/libre/sin-historial', () => {
    expect(describeMotivo({ pesoSugerido: 0, repsSugeridas: 0, motivo: 'off' }, 5)).toBeNull();
    expect(describeMotivo({ pesoSugerido: 0, repsSugeridas: 0, motivo: 'libre' }, 5)).toBeNull();
    expect(describeMotivo({ pesoSugerido: 0, repsSugeridas: 0, motivo: 'sin-historial' }, 5)).toBeNull();
  });
});
```

- [ ] **Step 2: Ejecutar y verlo fallar**

Run: `npx vitest run lib/progresion.test.ts -t "calcularSugerencia"`
Expected: FAIL — "calcularSugerencia is not exported".

- [ ] **Step 3: Implementar `calcularSugerencia` + `describeMotivo`**

Añade a `lib/progresion.ts`:

```ts
export type ModoProgresion = 'doble' | 'objetivo' | 'repite' | 'off';

export type Motivo = 'subio-peso' | 'subio-reps' | 'repite' | 'sin-historial' | 'libre' | 'off';

export interface Sugerencia {
  pesoSugerido: number;
  repsSugeridas: number;
  motivo: Motivo;
}

export interface SugerenciaInput {
  modo: ModoProgresion;
  /** Series de trabajo (sin calentamiento) de la última sesión del ejercicio, mismo gym. undefined = sin historial. */
  ultimo?: { peso: number; reps: number }[];
  /** Objetivo de la rutina. undefined = entreno libre. */
  objetivo?: { repsObjetivo?: number; repsObjetivoMin?: number };
  /** Salto de peso resuelto por inferirSalto. */
  salto: number;
  esCorporal: boolean;
}

function rango(objetivo: { repsObjetivo?: number; repsObjetivoMin?: number }): { min: number; tope: number } {
  const tope = objetivo.repsObjetivo ?? 0;
  const minRaw = objetivo.repsObjetivoMin ?? tope - 4;
  const min = Math.max(1, Math.min(minRaw, tope));
  return { min, tope };
}

export function calcularSugerencia(input: SugerenciaInput): Sugerencia {
  const { modo, ultimo, objetivo, salto, esCorporal } = input;
  const ultimoSet = ultimo && ultimo.length > 0 ? ultimo[ultimo.length - 1] : undefined;

  // Casos de paso directo: sin progresión.
  if (modo === 'off') {
    return { pesoSugerido: ultimoSet?.peso ?? 0, repsSugeridas: ultimoSet?.reps ?? 0, motivo: 'off' };
  }
  if (!objetivo) {
    return { pesoSugerido: ultimoSet?.peso ?? 0, repsSugeridas: ultimoSet?.reps ?? 0, motivo: 'libre' };
  }
  if (!ultimo || ultimo.length === 0) {
    return { pesoSugerido: 0, repsSugeridas: objetivo.repsObjetivo ?? 0, motivo: 'sin-historial' };
  }

  const basePeso = ultimoSet!.peso;
  const { min, tope } = rango(objetivo);
  const objetivoReps = modo === 'doble' ? tope : (objetivo.repsObjetivo ?? 0);
  const exito = ultimo.every((s) => s.reps >= objetivoReps);

  // Peso corporal: nunca toca el peso, progresa por reps.
  if (esCorporal) {
    if (exito) return { pesoSugerido: basePeso, repsSugeridas: ultimoSet!.reps + 1, motivo: 'subio-reps' };
    return { pesoSugerido: basePeso, repsSugeridas: objetivoReps, motivo: 'repite' };
  }

  if (modo === 'doble') {
    if (exito) return { pesoSugerido: basePeso + salto, repsSugeridas: min, motivo: 'subio-peso' };
    return { pesoSugerido: basePeso, repsSugeridas: Math.min(tope, ultimoSet!.reps + 1), motivo: 'subio-reps' };
  }

  // modo 'objetivo' | 'repite'
  if (exito) return { pesoSugerido: basePeso + salto, repsSugeridas: objetivoReps, motivo: 'subio-peso' };
  return { pesoSugerido: basePeso, repsSugeridas: objetivoReps, motivo: 'repite' };
}

/** Texto corto para el badge de la card de entreno. null = no mostrar badge. */
export function describeMotivo(s: Sugerencia, salto: number): string | null {
  switch (s.motivo) {
    case 'subio-peso': return `▲ +${salto} kg`;
    case 'subio-reps': return '▲ +1 rep';
    case 'repite': return '= repite';
    default: return null;
  }
}
```

- [ ] **Step 4: Ejecutar y verlo pasar**

Run: `npx vitest run lib/progresion.test.ts`
Expected: PASS (todos: inferirSalto + calcularSugerencia + describeMotivo).

- [ ] **Step 5: Commit**

```bash
git add lib/progresion.ts lib/progresion.test.ts
git commit -m "feat(progresion): calcularSugerencia (modos doble/objetivo/repite/off) + badge"
```

---

## Task 4: `getLastWorkingSets` en el repo de workouts

**Files:**
- Modify: `lib/repositories/workouts.ts:120-167`
- Test: `lib/repositories/workouts.test.ts`

- [ ] **Step 1: Escribir el test que falla**

En `lib/repositories/workouts.test.ts`, añade (imitando el estilo de los tests existentes de `getLastPerformance`; reutiliza sus helpers de creación de sesión/sets):

```ts
it('getLastWorkingSets devuelve las series de trabajo de la última sesión, sin calentamiento', async () => {
  const s1 = await startSession({ gymId: 'g1' });
  const le1 = await addLoggedExercise(s1.id, 'ex-1');
  await addSet(le1.id, { peso: 30, reps: 12, esCalentamiento: true });
  await addSet(le1.id, { peso: 40, reps: 12 });
  await addSet(le1.id, { peso: 40, reps: 11 });

  const res = await getLastWorkingSets('ex-1', undefined, 'g1');
  expect(res).toEqual([
    { peso: 40, reps: 12 },
    { peso: 40, reps: 11 },
  ]);
});

it('getLastWorkingSets filtra por gimnasio', async () => {
  const sA = await startSession({ gymId: 'gA' });
  const leA = await addLoggedExercise(sA.id, 'ex-2');
  await addSet(leA.id, { peso: 50, reps: 10 });
  const res = await getLastWorkingSets('ex-2', undefined, 'gB');
  expect(res).toBeUndefined();
});
```

Asegúrate de que los imports del fichero incluyan `getLastWorkingSets` (lo añadiremos), `startSession`, `addLoggedExercise`, `addSet`.

- [ ] **Step 2: Ejecutar y verlo fallar**

Run: `npx vitest run lib/repositories/workouts.test.ts -t "getLastWorkingSets"`
Expected: FAIL — "getLastWorkingSets is not exported".

- [ ] **Step 3: Implementar `getLastWorkingSets`**

En `lib/repositories/workouts.ts`, `findLastSetWithFecha` ya localiza el `loggedExercise` más reciente del ejercicio en ese gym. Refactoriza para reutilizar esa localización y añade la nueva función. Sustituye el bucle final de `findLastSetWithFecha` por una versión que devuelva el `loggedExercise` encontrado, y deriva de ahí ambas funciones:

```ts
/** Localiza el loggedExercise más reciente del ejercicio (filtrando por gym) y la fecha de su sesión. */
async function findLastLoggedExercise(
  exerciseId: string,
  excludeSessionId?: string,
  gymId?: string | null,
): Promise<{ le: LoggedExercise; fecha: number } | undefined> {
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
    if (sets.length > 0) return { le, fecha: fechaBySession.get(le.sessionId) ?? 0 };
  }
  return undefined;
}

/** Busca el último set (y la fecha de su sesión) del ejercicio, filtrando por gym. */
async function findLastSetWithFecha(
  exerciseId: string,
  excludeSessionId?: string,
  gymId?: string | null,
): Promise<{ set: LoggedSet; fecha: number } | undefined> {
  const found = await findLastLoggedExercise(exerciseId, excludeSessionId, gymId);
  if (!found) return undefined;
  const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(found.le.id).toArray());
  sets.sort((a, b) => b.orden - a.orden);
  return { set: sets[0], fecha: found.fecha };
}

/** Series de trabajo (sin calentamiento) de la última sesión del ejercicio en ese gym, en orden. */
export async function getLastWorkingSets(
  exerciseId: string,
  excludeSessionId?: string,
  gymId?: string | null,
): Promise<{ peso: number; reps: number }[] | undefined> {
  const found = await findLastLoggedExercise(exerciseId, excludeSessionId, gymId);
  if (!found) return undefined;
  const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(found.le.id).toArray())
    .filter((s) => !s.esCalentamiento)
    .sort((a, b) => a.orden - b.orden);
  if (sets.length === 0) return undefined;
  return sets.map((s) => ({ peso: s.peso, reps: s.reps }));
}
```

Deja `getLastSet` y `getLastPerformance` como están (siguen apoyándose en `findLastSetWithFecha`).

- [ ] **Step 4: Ejecutar los tests del repo y verlos pasar**

Run: `npx vitest run lib/repositories/workouts.test.ts`
Expected: PASS (los nuevos `getLastWorkingSets` y los previos de `getLastPerformance`/`getLastSet` siguen verdes).

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/workouts.ts lib/repositories/workouts.test.ts
git commit -m "feat(workouts): getLastWorkingSets (series de trabajo última sesión, mismo gym)"
```

---

## Task 5: Ajustes globales (modo + incrementos) en `lib/settings.ts`

**Files:**
- Modify: `lib/settings.ts`
- Test: `lib/settings.test.ts`

- [ ] **Step 1: Escribir los tests que fallan**

En `lib/settings.test.ts` añade (imita el setup existente; si el fichero limpia `localStorage` en `beforeEach`, reúsalo):

```ts
import {
  getModoProgresion, setModoProgresion,
  getIncrementos, setIncrementos,
} from '@/lib/settings';
import { INCREMENTO_DEFAULTS } from '@/lib/progresion';

describe('modo de progresión', () => {
  it('default = objetivo', () => {
    expect(getModoProgresion()).toBe('objetivo');
  });
  it('persiste el modo elegido', () => {
    setModoProgresion('doble');
    expect(getModoProgresion()).toBe('doble');
  });
  it('valor corrupto en storage → vuelve al default', () => {
    localStorage.setItem('gymlog.modoProgresion', 'basura');
    expect(getModoProgresion()).toBe('objetivo');
  });
});

describe('incrementos por equipamiento', () => {
  it('default = INCREMENTO_DEFAULTS', () => {
    expect(getIncrementos()).toEqual(INCREMENTO_DEFAULTS);
  });
  it('fusiona overrides parciales sobre los defaults', () => {
    setIncrementos({ maquina: 7.5 });
    expect(getIncrementos().maquina).toBe(7.5);
    expect(getIncrementos().barra).toBe(INCREMENTO_DEFAULTS.barra);
  });
  it('storage corrupto → defaults', () => {
    localStorage.setItem('gymlog.incrementos', '{no json');
    expect(getIncrementos()).toEqual(INCREMENTO_DEFAULTS);
  });
});
```

- [ ] **Step 2: Ejecutar y verlo fallar**

Run: `npx vitest run lib/settings.test.ts`
Expected: FAIL — funciones no exportadas.

- [ ] **Step 3: Implementar los nuevos ajustes**

En `lib/settings.ts`, añade tras lo existente (reutiliza `EVENT`/`subscribe` ya definidos):

```ts
import type { Equipment } from '@/lib/db/types';
import type { ModoProgresion } from '@/lib/progresion';
import { INCREMENTO_DEFAULTS } from '@/lib/progresion';

const KEY_MODO = 'gymlog.modoProgresion';
const KEY_INCR = 'gymlog.incrementos';
const MODOS: ModoProgresion[] = ['doble', 'objetivo', 'repite', 'off'];

export function getModoProgresion(): ModoProgresion {
  if (typeof localStorage === 'undefined') return 'objetivo';
  const v = localStorage.getItem(KEY_MODO);
  return (MODOS as string[]).includes(v ?? '') ? (v as ModoProgresion) : 'objetivo';
}

export function setModoProgresion(value: ModoProgresion): void {
  if (typeof localStorage === 'undefined') return;
  localStorage.setItem(KEY_MODO, value);
  window.dispatchEvent(new CustomEvent(EVENT));
}

export function getIncrementos(): Record<Equipment, number> {
  if (typeof localStorage === 'undefined') return { ...INCREMENTO_DEFAULTS };
  try {
    const raw = localStorage.getItem(KEY_INCR);
    if (!raw) return { ...INCREMENTO_DEFAULTS };
    return { ...INCREMENTO_DEFAULTS, ...(JSON.parse(raw) as Partial<Record<Equipment, number>>) };
  } catch {
    return { ...INCREMENTO_DEFAULTS };
  }
}

export function setIncrementos(partial: Partial<Record<Equipment, number>>): void {
  if (typeof localStorage === 'undefined') return;
  localStorage.setItem(KEY_INCR, JSON.stringify({ ...getIncrementos(), ...partial }));
  window.dispatchEvent(new CustomEvent(EVENT));
}

export function useModoProgresion(): [ModoProgresion, (v: ModoProgresion) => void] {
  const value = useSyncExternalStore(subscribe, getModoProgresion, () => 'objetivo' as ModoProgresion);
  return [value, setModoProgresion];
}

export function useIncrementos(): [Record<Equipment, number>, (p: Partial<Record<Equipment, number>>) => void] {
  const value = useSyncExternalStore(subscribe, getIncrementos, () => ({ ...INCREMENTO_DEFAULTS }));
  return [value, setIncrementos];
}
```

Mueve los `import` al inicio del fichero junto a los existentes.

- [ ] **Step 4: Ejecutar y verlo pasar**

Run: `npx vitest run lib/settings.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lib/settings.ts lib/settings.test.ts
git commit -m "feat(settings): modo de progresión e incrementos por equipamiento (localStorage)"
```

---

## Task 6: Autorrelleno + badge en `LoggedExerciseCard`

**Files:**
- Modify: `components/logged-exercise-card.tsx`
- Test: `components/logged-exercise-card.test.tsx`

- [ ] **Step 1: Escribir el test que falla**

En `components/logged-exercise-card.test.tsx` (imita el setup del fichero: seed de un `Exercise`, un `LoggedExercise` y, para el historial, una sesión previa con sets). Añade:

```ts
it('autorrellena la primera serie con la sugerencia y muestra el badge ▲ +5 kg', async () => {
  // Ejercicio de máquina, modo objetivo (default). Historial: 40×12 ×2 (éxito) en gym g1.
  await db.exercises.put({
    id: 'ex-1', userId: null, nombre: 'Press', grupoMuscular: 'pecho',
    equipamiento: 'maquina', tipo: 'compuesto', esPersonalizado: false,
    updatedAt: 1, deletedAt: null,
  });
  const prev = await startSession({ routineId: 'r1', gymId: 'g1' });
  const lePrev = await addLoggedExercise(prev.id, 'ex-1');
  await addSet(lePrev.id, { peso: 40, reps: 12 });
  await addSet(lePrev.id, { peso: 40, reps: 12 });
  // objetivo de rutina
  await db.routineExercises.put({
    id: 're-1', routineId: 'r1', exerciseId: 'ex-1', orden: 0,
    seriesObjetivo: 3, repsObjetivo: 12, updatedAt: 1, deletedAt: null,
  });

  const sesion = await startSession({ routineId: 'r1', gymId: 'g1' });
  const le = await addLoggedExercise(sesion.id, 'ex-1');

  render(<LoggedExerciseCard loggedExercise={le} sessionId={sesion.id} gymId="g1" routineId="r1" />);

  // El badge aparece (éxito → +5 kg porque máquina infiere... aquí solo 1 peso → default 5).
  expect(await screen.findByText('▲ +5 kg')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'Añadir serie' }));

  const peso = await screen.findByLabelText('Peso');
  expect((peso as HTMLInputElement).value).toBe('45');
});
```

Ajusta imports (`db`, `startSession`, `addLoggedExercise`, `addSet`, `render`, `screen`, `userEvent`) según lo que ya use el fichero de test.

- [ ] **Step 2: Ejecutar y verlo fallar**

Run: `npx vitest run components/logged-exercise-card.test.tsx -t "autorrellena la primera serie"`
Expected: FAIL — el badge no existe y el valor autorrellenado es `40` (último peso) en vez de `45`.

- [ ] **Step 3: Conectar el módulo de progresión en la card**

En `components/logged-exercise-card.tsx`:

1. Amplía imports:

```ts
import {
  addSet, updateSet, softDeleteSet, listExerciseSets, getLastPerformance, getLastWorkingSets,
  softDeleteLoggedExercise,
} from '@/lib/repositories/workouts';
import { calcularSugerencia, describeMotivo, inferirSalto } from '@/lib/progresion';
import { useModoProgresion, useIncrementos } from '@/lib/settings';
```

(Quita `getLastSet` del import si deja de usarse.)

2. Dentro del componente, tras los `useLiveQuery` existentes, calcula la sugerencia:

```ts
const [modo] = useModoProgresion();
const [incrementos] = useIncrementos();

const sugerencia = useLiveQuery(async () => {
  if (!ejercicio) return undefined;
  const ultimo = await getLastWorkingSets(loggedExercise.exerciseId, sessionId, gymId);
  const salto = inferirSalto((ultimo ?? []).map((s) => s.peso), {
    equipamiento: ejercicio.equipamiento,
    defaults: incrementos,
    override: ejercicio.incrementoKg,
  });
  const sug = calcularSugerencia({
    modo,
    ultimo,
    objetivo: routineId ? objetivo ?? undefined : undefined,
    salto,
    esCorporal: ejercicio.equipamiento === 'peso_corporal',
  });
  return { sug, badge: describeMotivo(sug, salto) };
}, [ejercicio, loggedExercise.exerciseId, sessionId, gymId, routineId, objetivo, modo, incrementos]);
```

3. Cambia `añadirSerie` para usar la sugerencia en la primera serie:

```ts
async function añadirSerie() {
  const actuales = sets ?? [];
  if (actuales.length > 0) {
    const ultimaSerie = actuales[actuales.length - 1];
    await addSet(loggedExercise.id, { peso: ultimaSerie.peso, reps: ultimaSerie.reps });
  } else {
    const sug = sugerencia?.sug;
    await addSet(loggedExercise.id, { peso: sug?.pesoSugerido ?? 0, reps: sug?.repsSugeridas ?? 0 });
  }
  setRestKey((k) => k + 1);
}
```

4. Muestra el badge dentro del bloque "Última vez / Objetivo". Tras el `<p>` del OBJETIVO (línea ~96), antes de cerrar ese `<div>`:

```tsx
{sugerencia?.badge && (
  <p className="label-mono text-[10px] font-semibold text-primary">
    {sugerencia.badge}
  </p>
)}
```

Y amplía la condición del contenedor para que el bloque aparezca también cuando solo hay badge:

```tsx
{(ultima || objetivo || sugerencia?.badge) && (
```

- [ ] **Step 4: Ejecutar el test y verlo pasar**

Run: `npx vitest run components/logged-exercise-card.test.tsx`
Expected: PASS (el nuevo test y los previos de la card).

- [ ] **Step 5: Commit**

```bash
git add components/logged-exercise-card.tsx components/logged-exercise-card.test.tsx
git commit -m "feat(entreno): autorrelleno con sugerencia de progresión + badge explicativo"
```

---

## Task 7: UI de configuración (rango, override, sección Ajustes)

**Files:**
- Modify: `components/routine-day-exercise-row.tsx`
- Modify: `components/exercise-form.tsx`
- Modify: `app/ajustes/page.tsx`

> Esta tarea es de cableado de UI (formularios sobre repos ya testeados). Verificación manual al final; sin test nuevo unitario salvo el de la card ya cubierto.

- [ ] **Step 1: Campo "Reps mín" en la fila de rutina**

En `components/routine-day-exercise-row.tsx`, el grid actual es `grid-cols-3` (Series / Reps / Descanso). Cambia a `grid-cols-2` con dos filas, o añade una cuarta celda. Implementación mínima: convierte el grid a `grid-cols-2` y añade "Reps mín" junto a "Reps". Reemplaza el bloque `<div className="grid grid-cols-3 gap-2">…</div>` por:

```tsx
<div className="grid grid-cols-2 gap-2">
  <div className="space-y-1">
    <Label htmlFor={`series-${routineExercise.id}`} className="text-xs">Series</Label>
    <Input id={`series-${routineExercise.id}`} inputMode="numeric" defaultValue={routineExercise.seriesObjetivo ?? ''}
      onChange={(e) => updateRoutineExercise(routineExercise.id, { seriesObjetivo: parseNum(e.target.value) })} />
  </div>
  <div className="space-y-1">
    <Label htmlFor={`descanso-${routineExercise.id}`} className="text-xs">Descanso (segs)</Label>
    <Input id={`descanso-${routineExercise.id}`} inputMode="numeric" defaultValue={routineExercise.descansoSegundos ?? ''}
      onChange={(e) => updateRoutineExercise(routineExercise.id, { descansoSegundos: parseNum(e.target.value) })} />
  </div>
  <div className="space-y-1">
    <Label htmlFor={`repsmin-${routineExercise.id}`} className="text-xs">Reps mín</Label>
    <Input id={`repsmin-${routineExercise.id}`} inputMode="numeric" defaultValue={routineExercise.repsObjetivoMin ?? ''}
      onChange={(e) => updateRoutineExercise(routineExercise.id, { repsObjetivoMin: parseNum(e.target.value) })} />
  </div>
  <div className="space-y-1">
    <Label htmlFor={`reps-${routineExercise.id}`} className="text-xs">Reps (tope)</Label>
    <Input id={`reps-${routineExercise.id}`} inputMode="numeric" defaultValue={routineExercise.repsObjetivo ?? ''}
      onChange={(e) => updateRoutineExercise(routineExercise.id, { repsObjetivo: parseNum(e.target.value) })} />
  </div>
</div>
```

- [ ] **Step 2: Campo "Incremento (kg)" override en la ficha de ejercicio**

En `components/exercise-form.tsx`:

1. Añade estado tras `notas`:

```ts
const [incrementoKg, setIncrementoKg] = useState(existing?.incrementoKg?.toString() ?? '');
```

2. En el objeto `data` del submit, añade:

```ts
incrementoKg: incrementoKg.trim() === '' ? undefined : Math.max(0, Number(incrementoKg)),
```

3. Antes del `{error && …}`, añade el campo (con `import { parseDecimal } from '@/lib/num'` si lo prefieres, pero aquí basta el `Number` de arriba):

```tsx
<div className="space-y-1">
  <Label htmlFor="incremento">Incremento al progresar (kg, opcional)</Label>
  <Input id="incremento" inputMode="decimal" value={incrementoKg} onChange={(e) => setIncrementoKg(e.target.value)} />
</div>
```

- [ ] **Step 3: Sección "Progresión" en Ajustes**

En `app/ajustes/page.tsx`:

1. Imports:

```ts
import { useModoProgresion, useIncrementos } from '@/lib/settings';
import { EQUIPMENTS } from '@/lib/db/types';
import { equipmentLabel } from '@/lib/labels';
import type { ModoProgresion } from '@/lib/progresion';
```

2. Dentro del componente:

```ts
const [modo, setModo] = useModoProgresion();
const [incrementos, setIncrementos] = useIncrementos();
const MODO_LABEL: Record<ModoProgresion, string> = {
  doble: 'Doble progresión (rango de reps)',
  objetivo: 'Objetivo + incremento',
  repite: 'Repite o sube',
  off: 'Desactivada',
};
```

3. Añade una `<section>` tras la de "Rutinas":

```tsx
<section className="space-y-3">
  <h2 className="label-mono text-[11px] text-muted-foreground">Progresión</h2>
  <label className="brutal-box flex items-center justify-between gap-3 px-3 py-2.5">
    <span className="font-semibold">Modo</span>
    <select
      className="border-2 border-foreground bg-card p-1 text-sm"
      value={modo}
      onChange={(e) => setModo(e.target.value as ModoProgresion)}
    >
      {(Object.keys(MODO_LABEL) as ModoProgresion[]).map((m) => (
        <option key={m} value={m}>{MODO_LABEL[m]}</option>
      ))}
    </select>
  </label>
  <div className="brutal-box divide-y-2 divide-foreground">
    {EQUIPMENTS.filter((eq) => eq !== 'peso_corporal' && eq !== 'otro').map((eq) => (
      <label key={eq} className="flex items-center justify-between gap-3 px-3 py-2">
        <span className="text-sm">{equipmentLabel[eq]}</span>
        <input
          type="number"
          step="0.5"
          min="0"
          className="w-20 border-2 border-foreground bg-card p-1 text-right text-sm tabular-nums"
          value={incrementos[eq]}
          onChange={(e) => setIncrementos({ [eq]: Math.max(0, Number(e.target.value)) })}
        />
      </label>
    ))}
  </div>
  <p className="label-mono text-[10px] text-muted-foreground">
    Salto por defecto cuando no hay historial. Con historial, la app lo deduce de tus pesos.
  </p>
</section>
```

- [ ] **Step 4: Verificación de tipos y lint**

Run: `npx tsc --noEmit && npx eslint components/routine-day-exercise-row.tsx components/exercise-form.tsx app/ajustes/page.tsx`
Expected: sin errores.

- [ ] **Step 5: Commit**

```bash
git add components/routine-day-exercise-row.tsx components/exercise-form.tsx app/ajustes/page.tsx
git commit -m "feat(progresion): UI de rango, override por ejercicio y ajustes de progresión"
```

---

## Task 8: Verificación final

- [ ] **Step 1: Suite completa**

Run: `npx vitest run`
Expected: todo verde.

- [ ] **Step 2: Lint + typecheck del proyecto**

Run: `npx tsc --noEmit && npm run lint`
Expected: sin errores.

- [ ] **Step 3: Smoke manual (dev server)**

Run: `npm run dev` y comprueba en el navegador (móvil-first):
- Ajustes → sección "Progresión": cambiar modo y un incremento; recargar y que persista.
- En una rutina: aparece "Reps mín" y "Reps (tope)"; guardar.
- Ficha de un ejercicio: campo "Incremento al progresar (kg)".
- Entrenar desde una rutina con historial: la primera serie arranca con el peso/reps sugeridos y se ve el badge (▲ +N kg / ▲ +1 rep / = repite). En entreno libre no hay badge y arranca con el último peso.

---

## Self-Review (hecho)

**Cobertura del spec:**
- Regla configurable global → Task 5 (settings) + Task 7 (UI Ajustes). ✓
- 4 modos `doble/objetivo/repite/off` → Task 3 (`calcularSugerencia`). ✓
- Incremento inferido + override + default por equipamiento → Task 2 (`inferirSalto`) + Task 1 (`incrementoKg`) + Task 5 (defaults). ✓
- Rango `repsObjetivoMin` (default tope−4) → Task 1 (campo) + Task 3 (`rango()`) + Task 7 (UI). ✓
- Autorrelleno + badge explicativo → Task 6. ✓
- Solo en rutina; libre = comportamiento actual → Task 3 (motivo `libre`) + Task 6 (`objetivo` solo si `routineId`). ✓
- Éxito ignora calentamiento; filtra por gym → Task 4 (`getLastWorkingSets`). ✓
- Peso corporal progresa por reps → Task 3. ✓
- Tests (puro exhaustivo + repo + componente) → Tasks 2,3,4,6. ✓

**Sin placeholders:** todo el código está completo en cada paso.

**Consistencia de tipos:** `ModoProgresion`/`Sugerencia`/`Motivo` definidos en Task 3 y consumidos igual en Tasks 5/6/7. `getLastWorkingSets` devuelve `{peso,reps}[]`, que es exactamente lo que espera `SugerenciaInput.ultimo`. `inferirSalto` recibe `number[]` (mapeado desde los pesos) y `Record<Equipment, number>` (de `getIncrementos`). ✓
