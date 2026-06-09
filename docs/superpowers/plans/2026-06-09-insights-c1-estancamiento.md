# Insights C1 — Estancamiento + deload (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detectar cuándo un ejercicio está estancado (mejor 1RM estimado sin mejorar en N sesiones) y avisar de forma informativa (badge al entrenar + lista en Progreso) con un consejo de deload.

**Architecture:** Función pura `detectarEstancamiento` en `lib/insights.ts` sobre los `ExerciseProgressPoint` que ya produce `getExerciseProgress`. Un helper `listEstancados` en `stats.ts` recorre los ejercicios entrenados (filtrados por gym) y devuelve los estancados. UI: badge en `LoggedExerciseCard` y sección en Progreso. No toca datos/sync/schema.

**Tech Stack:** Next.js 16 + React 19 + TypeScript · Dexie · Recharts (no usado aquí) · Vitest + fake-indexeddb + Testing Library.

**Nota:** Es la fase **C1** del spec `docs/superpowers/specs/2026-06-08-insights-design.md` (módulo 1). C0 (ajustes sincronizados) ya está en `main`. C2/C3 vienen después. Todo se deriva de datos existentes — sin cambios de Dexie/Drizzle/sync/backup.

---

## File Structure

- **Create** `lib/insights.ts` — `detectarEstancamiento` (pura) + `DELOAD_CONSEJO` + tipos.
- **Create** `lib/insights.test.ts` — tests de la función pura.
- **Modify** `lib/repositories/stats.ts` — `listEstancados(gymId?)` + tipo `Estancado`.
- **Modify** `lib/repositories/stats.test.ts` — test de `listEstancados`.
- **Create** `components/estancados-list.tsx` — lista de ejercicios estancados.
- **Modify** `components/logged-exercise-card.tsx` — badge ESTANCADO.
- **Modify** `components/logged-exercise-card.test.tsx` — test del badge.
- **Modify** `app/progreso/page.tsx` — sección "Ejercicios estancados".

---

## Task 1: `detectarEstancamiento` (módulo puro)

**Files:**
- Create: `lib/insights.ts`
- Create: `lib/insights.test.ts`

- [ ] **Step 1: Write the failing test** — `lib/insights.test.ts`

```ts
import { describe, it, expect } from 'vitest';
import { detectarEstancamiento } from '@/lib/insights';

// Helper: construye puntos con fecha incremental y un mejor1RM dado.
function pts(rms: number[]): { fecha: number; maxPeso: number; mejor1RM: number; volumen: number }[] {
  return rms.map((rm, i) => ({ fecha: 1000 + i, maxPeso: 0, mejor1RM: rm, volumen: 0 }));
}

describe('detectarEstancamiento', () => {
  it('datos insuficientes (< n+1 sesiones) → no estancado', () => {
    expect(detectarEstancamiento(pts([100, 100, 100]), 3)).toEqual({
      estancado: false, sesionesSinMejora: 0, ultimaMejoraFecha: null,
    });
  });

  it('estancado: subió a 105 y luego se quedó plano', () => {
    const r = detectarEstancamiento(pts([105, 100, 100, 100]), 3);
    expect(r.estancado).toBe(true);
    expect(r.sesionesSinMejora).toBe(3);
    expect(r.ultimaMejoraFecha).toBe(1000); // el punto con 105 (índice 0)
  });

  it('empate exacto cuenta como estancado', () => {
    expect(detectarEstancamiento(pts([100, 100, 100, 100]), 3).estancado).toBe(true);
  });

  it('mejorando: nuevo máximo en la última sesión → no estancado', () => {
    const r = detectarEstancamiento(pts([100, 100, 100, 105]), 3);
    expect(r.estancado).toBe(false);
    expect(r.sesionesSinMejora).toBe(0);
    expect(r.ultimaMejoraFecha).toBe(1003);
  });

  it('mejora dentro de la ventana reciente → no estancado', () => {
    // previo = max(primeras 2) = 100; reciente = max(últimas 3) incluye 110
    const r = detectarEstancamiento(pts([100, 100, 110, 108]), 3);
    expect(r.estancado).toBe(false);
  });
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/insights.test.ts`
Expected: FAIL — module missing.

- [ ] **Step 3: Implement** — `lib/insights.ts`

```ts
import type { ExerciseProgressPoint } from '@/lib/repositories/stats';

/** Consejo mostrado cuando un ejercicio está estancado. */
export const DELOAD_CONSEJO = 'Prueba bajar ~10% y vuelve a subir, o cambia de ejercicio.';

export interface Estancamiento {
  estancado: boolean;
  /** Sesiones transcurridas desde la última que batió el máximo histórico de 1RM. */
  sesionesSinMejora: number;
  /** Fecha (epoch ms) de esa última mejora; null si datos insuficientes. */
  ultimaMejoraFecha: number | null;
}

/**
 * Un ejercicio está estancado si su mejor 1RM estimado no mejora en las últimas `n` sesiones:
 * el máximo de las últimas `n` no supera al máximo de las anteriores. Requiere ≥ n+1 sesiones.
 * `points` debe venir en orden cronológico (como los devuelve getExerciseProgress).
 */
export function detectarEstancamiento(points: ExerciseProgressPoint[], n = 3): Estancamiento {
  if (points.length < n + 1) {
    return { estancado: false, sesionesSinMejora: 0, ultimaMejoraFecha: null };
  }
  // Última mejora = último punto que batió el máximo histórico previo.
  let runningMax = -Infinity;
  let lastImprovementIdx = 0;
  for (let i = 0; i < points.length; i++) {
    if (points[i].mejor1RM > runningMax) {
      runningMax = points[i].mejor1RM;
      lastImprovementIdx = i;
    }
  }
  const sesionesSinMejora = points.length - 1 - lastImprovementIdx;
  const ultimaMejoraFecha = points[lastImprovementIdx].fecha;

  const mejorReciente = Math.max(...points.slice(points.length - n).map((p) => p.mejor1RM));
  const mejorPrevio = Math.max(...points.slice(0, points.length - n).map((p) => p.mejor1RM));

  return { estancado: mejorReciente <= mejorPrevio, sesionesSinMejora, ultimaMejoraFecha };
}
```

(`import type` evita un ciclo en runtime: `stats.ts` importará `detectarEstancamiento` de aquí, y aquí solo se importa el TIPO `ExerciseProgressPoint`, que se borra al compilar.)

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run lib/insights.test.ts`
Expected: PASS (5). Also `npx tsc --noEmit` clean.

- [ ] **Step 5: Commit**

```bash
git add lib/insights.ts lib/insights.test.ts
git commit -m "feat(insights): detectarEstancamiento (mejor 1RM sin mejorar en N sesiones)"
```

---

## Task 2: `listEstancados` en stats.ts

**Files:**
- Modify: `lib/repositories/stats.ts`
- Modify: `lib/repositories/stats.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/stats.ts` to confirm the `activo` helper and `db` import exist (used by `getVolumeByMuscle`/`getPeriodSummary`), the exact signature of `getExerciseProgress(exerciseId, gymId?, sinceTs?)`, and the gym-filtering pattern in `getVolumeByMuscle` (sessions → sessionIds → les → exerciseIds). Read `lib/repositories/stats.test.ts` for its setup style (how it seeds sessions/loggedExercises/loggedSets and clears the db).

- [ ] **Step 1: Write the failing test** — append to `lib/repositories/stats.test.ts` (adapt the seeding to the file's real helpers; the pattern below mirrors how other stats tests build history):

```ts
import { listEstancados } from '@/lib/repositories/stats';

it('listEstancados detecta un ejercicio con 1RM plano (4 sesiones) y respeta el gym', async () => {
  // Ejercicio ex-est: 4 sesiones, mismo peso×reps → mejor1RM plano → estancado.
  await db.exercises.put({
    id: 'ex-est', userId: null, nombre: 'Press estancado', grupoMuscular: 'pecho',
    equipamiento: 'maquina', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null,
  });
  for (let i = 0; i < 4; i++) {
    const s = { id: `s${i}`, userId: null, gymId: 'g1', fecha: 1000 + i * 86400000, updatedAt: 1, deletedAt: null };
    await db.workoutSessions.put(s);
    const le = { id: `le${i}`, sessionId: s.id, exerciseId: 'ex-est', orden: 0, updatedAt: 1, deletedAt: null };
    await db.loggedExercises.put(le);
    await db.loggedSets.put({ id: `set${i}`, loggedExerciseId: le.id, orden: 0, peso: 40, reps: 10, updatedAt: 1, deletedAt: null });
  }

  const enG1 = await listEstancados('g1');
  expect(enG1.map((e) => e.exerciseId)).toContain('ex-est');
  const enG2 = await listEstancados('g2');
  expect(enG2.map((e) => e.exerciseId)).not.toContain('ex-est'); // otro gym, sin historial
});
```
(Ensure `db` is imported at the top of the test file — it should already be.)

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/repositories/stats.test.ts -t "listEstancados"`
Expected: FAIL — `listEstancados` not exported.

- [ ] **Step 3: Implement** — in `lib/repositories/stats.ts`:

1. Add the import near the top (with the other imports): `import { detectarEstancamiento } from '@/lib/insights';`
2. At the end of the file add:

```ts
export interface Estancado {
  exerciseId: string;
  nombre: string;
  sesionesSinMejora: number;
  ultimaMejoraFecha: number | null;
}

/** Ejercicios entrenados (en ese gym) cuyo mejor 1RM estimado está estancado. */
export async function listEstancados(gymId?: string | null): Promise<Estancado[]> {
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId);
  const sessionIds = new Set(sessions.map((s) => s.id));
  if (sessionIds.size === 0) return [];
  const les = activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  const exerciseIds = [...new Set(les.map((le) => le.exerciseId))];
  const out: Estancado[] = [];
  for (const exerciseId of exerciseIds) {
    const points = await getExerciseProgress(exerciseId, gymId);
    const e = detectarEstancamiento(points);
    if (!e.estancado) continue;
    const ex = await db.exercises.get(exerciseId);
    out.push({
      exerciseId,
      nombre: ex?.nombre ?? '—',
      sesionesSinMejora: e.sesionesSinMejora,
      ultimaMejoraFecha: e.ultimaMejoraFecha,
    });
  }
  return out.sort((a, b) => b.sesionesSinMejora - a.sesionesSinMejora);
}
```

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run lib/repositories/stats.test.ts`
Expected: PASS (new + existing). Also `npx tsc --noEmit` clean (confirm no circular-import runtime error — the insights↔stats link is type-only on the insights side).

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/stats.ts lib/repositories/stats.test.ts
git commit -m "feat(insights): listEstancados (ejercicios con 1RM estancado, por gym)"
```

---

## Task 3: Sección "Ejercicios estancados" en Progreso

**Files:**
- Create: `components/estancados-list.tsx`
- Modify: `app/progreso/page.tsx`

> UI sobre un repo ya testeado; verificación por tsc/lint + suite. No requiere test unitario nuevo (la lógica está cubierta en Tasks 1-2).

- [ ] **Step 1: Create the component** — `components/estancados-list.tsx`

```tsx
import type { Estancado } from '@/lib/repositories/stats';

export function EstancadosList({ data }: { data: Estancado[] }) {
  if (data.length === 0) {
    return <p className="text-muted-foreground">Ningún ejercicio estancado ahora mismo.</p>;
  }
  return (
    <ul className="brutal-box divide-y-2 divide-foreground">
      {data.map((e) => (
        <li key={e.exerciseId} className="flex items-center justify-between gap-2 px-3 py-2">
          <span className="font-medium">{e.nombre}</span>
          <span className="label-mono text-[10px] text-destructive">
            {e.sesionesSinMejora} sesiones sin mejorar
          </span>
        </li>
      ))}
    </ul>
  );
}
```

- [ ] **Step 2: Wire into Progreso** — in `app/progreso/page.tsx`:

1. Add imports:
```ts
import { EstancadosList } from '@/components/estancados-list';
import { listEstancados } from '@/lib/repositories/stats';
```
2. Add a live query alongside the existing ones (which already destructure `gymId` from the gym filter):
```ts
const estancados = useLiveQuery(() => listEstancados(gymId), [gymId]);
```
3. Add a section (place it after the `Volumen semanal` section and before `Por ejercicio`, matching the existing `<section className="space-y-2">` + `<h2 className="label-mono text-[10px] text-muted-foreground">` markup):
```tsx
<section className="space-y-2">
  <h2 className="label-mono text-[10px] text-muted-foreground">Ejercicios estancados</h2>
  <EstancadosList data={estancados ?? []} />
</section>
```

- [ ] **Step 3: Typecheck + lint**

Run: `npx tsc --noEmit && npx eslint components/estancados-list.tsx app/progreso/page.tsx`
Expected: clean.

- [ ] **Step 4: Commit**

```bash
git add components/estancados-list.tsx app/progreso/page.tsx
git commit -m "feat(insights): sección Ejercicios estancados en Progreso"
```

---

## Task 4: Badge ESTANCADO al entrenar

**Files:**
- Modify: `components/logged-exercise-card.tsx`
- Modify: `components/logged-exercise-card.test.tsx`

- [ ] **Step 0: READ FIRST**

Read `components/logged-exercise-card.tsx` (the `useLiveQuery` calls, the `ÚLTIMA VEZ / OBJETIVO` block where the progression badge `sugerencia?.badge` is rendered, and the props — `gymId?: string`). Read its test file for the seeding/render style. The card already imports from `@/lib/repositories/workouts` and `@/lib/progresion`.

- [ ] **Step 1: Write the failing test** — add to `components/logged-exercise-card.test.tsx` (mirror the file's real seeding helpers):

```ts
it('muestra el badge ESTANCADO cuando el 1RM lleva 4 sesiones plano', async () => {
  await db.exercises.put({
    id: 'ex-st', userId: null, nombre: 'Curl', grupoMuscular: 'biceps',
    equipamiento: 'mancuerna', tipo: 'aislamiento', esPersonalizado: false, updatedAt: 1, deletedAt: null,
  });
  for (let i = 0; i < 4; i++) {
    const s = { id: `ps${i}`, userId: null, gymId: 'g1', fecha: 1000 + i * 86400000, updatedAt: 1, deletedAt: null };
    await db.workoutSessions.put(s);
    const le = { id: `ple${i}`, sessionId: s.id, exerciseId: 'ex-st', orden: 0, updatedAt: 1, deletedAt: null };
    await db.loggedExercises.put(le);
    await db.loggedSets.put({ id: `pset${i}`, loggedExerciseId: le.id, orden: 0, peso: 12, reps: 10, updatedAt: 1, deletedAt: null });
  }
  const sesion = await startSession({ gymId: 'g1' });
  const le = await addLoggedExercise(sesion.id, 'ex-st');
  render(<LoggedExerciseCard loggedExercise={le} sessionId={sesion.id} gymId="g1" />);
  expect(await screen.findByText('ESTANCADO')).toBeInTheDocument();
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run components/logged-exercise-card.test.tsx -t "ESTANCADO"`
Expected: FAIL — no such text.

- [ ] **Step 3: Implement** — in `components/logged-exercise-card.tsx`:

1. Add imports:
```ts
import { getExerciseProgress } from '@/lib/repositories/stats';
import { detectarEstancamiento, DELOAD_CONSEJO } from '@/lib/insights';
```
2. Add a live query alongside the existing ones:
```ts
const estancado = useLiveQuery(async () => {
  const points = await getExerciseProgress(loggedExercise.exerciseId, gymId);
  return detectarEstancamiento(points).estancado;
}, [loggedExercise.exerciseId, gymId]);
```
3. Render the badge inside the `ÚLTIMA VEZ / OBJETIVO` block, after the progression badge `{sugerencia?.badge && (...)}`:
```tsx
{estancado && (
  <p className="label-mono text-[10px] font-semibold text-destructive" title={DELOAD_CONSEJO}>
    ESTANCADO · {DELOAD_CONSEJO}
  </p>
)}
```
4. Widen the block's render condition so it shows when only the estancado badge exists. The current condition is `(ultima || objetivo || sugerencia?.badge)` — change it to `(ultima || objetivo || sugerencia?.badge || estancado)`.

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run components/logged-exercise-card.test.tsx`
Expected: PASS (new + existing card tests, incl. the progression "▲ +5 kg" test). Also `npx tsc --noEmit` + `npx eslint components/logged-exercise-card.tsx`.

- [ ] **Step 5: Commit**

```bash
git add components/logged-exercise-card.tsx components/logged-exercise-card.test.tsx
git commit -m "feat(insights): badge ESTANCADO + consejo de deload al entrenar"
```

---

## Task 5: Verificación final

- [ ] **Step 1: Full suite**

Run: `npx vitest run`
Expected: todo verde (insights + stats listEstancados + card badge + previos).

- [ ] **Step 2: Types + lint**

Run: `npx tsc --noEmit && npm run lint`
Expected: sin errores.

- [ ] **Step 3: Build**

Run: `npm run build`
Expected: build OK (`--webpack`).

---

## Self-Review (hecho)

**Cobertura del spec (módulo 1):**
- `detectarEstancamiento(points, n=3)` con regla "mejor 1RM no mejora en n sesiones", ≥ n+1 sesiones, devuelve `{ estancado, sesionesSinMejora, ultimaMejoraFecha }` → Task 1. ✓
- `DELOAD_CONSEJO` constante → Task 1. ✓
- Badge `ESTANCADO` + consejo al entrenar, informativo, sin tocar el autorrelleno de A → Task 4. ✓
- Sección "Ejercicios estancados" en Progreso con "N sesiones sin mejorar" → Tasks 2-3. ✓
- `listEstancados(gymId?)` recorre ejercicios entrenados y filtra por gym → Task 2. ✓
- Tests puro (insuficiente/estancado/mejorando/empate) + repo (gym) + componente (badge) → Tasks 1, 2, 4. ✓

**Sin placeholders:** todo el código está completo en cada paso.

**Consistencia de tipos:** `ExerciseProgressPoint` (de stats.ts) consumido como `import type` en insights.ts (sin ciclo runtime). `detectarEstancamiento` devuelve `Estancamiento`; `listEstancados` devuelve `Estancado[]` (tipo nuevo en stats.ts). El badge usa `detectarEstancamiento(...).estancado` y `DELOAD_CONSEJO`. `getExerciseProgress(exerciseId, gymId)` con la firma real (gymId opcional). ✓

**Riesgo señalado:** Task 4 toca `LoggedExerciseCard` (ya con la lógica de A); el badge nuevo es aditivo (otra `useLiveQuery` + un `<p>`), no altera el autorrelleno ni el badge de progresión.
