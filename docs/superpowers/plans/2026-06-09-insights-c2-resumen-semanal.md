# Insights C2 — Resumen semanal proactivo (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mostrar de forma proactiva el resumen de la semana: sesiones vs objetivo (adherencia), PRs batidos esta semana y tendencia de volumen vs la semana previa — mini en Home y completo en Progreso.

**Architecture:** Dos funciones de stats puras-sobre-Dexie (`getWeeklySummary`, `getPRsThisWeek`) que reutilizan `inicioSemana`/`setsDeEjercicio`/`estimar1RM`. El objetivo semanal es un ajuste sincronizado (`useSetting('objetivoSemanal', 3)`, de C0). UI: `WeeklyDigestMini` en Home y `WeeklyDigest` (gym-filtrado) en Progreso. No toca datos/sync/schema.

**Tech Stack:** Next.js 16 + React 19 + TypeScript · Dexie · Vitest + fake-indexeddb + Testing Library.

**Nota:** Fase **C2** del spec `docs/superpowers/specs/2026-06-08-insights-design.md` (módulo 2). C0 y C1 ya están en `main` y desplegados. Las funciones de fecha usan `inicioSemana` (lunes, hora local); para testabilidad las funciones nuevas aceptan un `now` opcional (default `Date.now()`).

---

## File Structure

- **Modify** `lib/repositories/stats.ts` — `getWeeklySummary` + `getPRsThisWeek` + tipos `WeeklySummary`/`PRSemana`.
- **Modify** `lib/repositories/stats.test.ts` — tests de ambas.
- **Create** `components/weekly-digest-mini.tsx` — tarjeta compacta para Home.
- **Modify** `app/page.tsx` — montar `WeeklyDigestMini`.
- **Create** `components/weekly-digest.tsx` — versión completa para Progreso (gym-filtrada).
- **Modify** `app/progreso/page.tsx` — montar `WeeklyDigest`.
- **Modify** `app/ajustes/page.tsx` — input "Objetivo semanal" (useSetting).

---

## Task 1: `getWeeklySummary` en stats.ts

**Files:**
- Modify: `lib/repositories/stats.ts`
- Modify: `lib/repositories/stats.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/stats.ts`: the private `inicioSemana(ts)` (Monday 00:00 local), the `activo` helper, `db` import, and the per-session volume pattern in `getWeeklyVolume` (les → loggedSets → sum peso*reps). Read `lib/repositories/stats.test.ts` for seeding style.

- [ ] **Step 1: Write the failing test** — append to `lib/repositories/stats.test.ts`:

```ts
import { getWeeklySummary } from '@/lib/repositories/stats';

// 2026-06-10 es miércoles. Semana actual: lun 8 … dom 14. Semana previa: lun 1 … dom 7.
const NOW_WED = new Date('2026-06-10T12:00:00').getTime();
const DAY = 86400000;

it('getWeeklySummary cuenta sesiones de la semana y compara volumen vs previa', async () => {
  // Esta semana: 2 sesiones (mié 10, lun 8). Semana previa: 1 sesión (mié 3).
  const seed = async (id: string, fecha: number, peso: number) => {
    await db.workoutSessions.put({ id, userId: null, gymId: 'g1', fecha, updatedAt: 1, deletedAt: null });
    const le = { id: `${id}-le`, sessionId: id, exerciseId: 'ws-ex', orden: 0, updatedAt: 1, deletedAt: null };
    await db.loggedExercises.put(le);
    await db.loggedSets.put({ id: `${id}-set`, loggedExerciseId: le.id, orden: 0, peso, reps: 10, updatedAt: 1, deletedAt: null });
  };
  await seed('ws-a', NOW_WED, 50);          // esta semana → vol 500
  await seed('ws-b', NOW_WED - 2 * DAY, 40); // esta semana (lun) → vol 400
  await seed('ws-c', NOW_WED - 7 * DAY, 30); // semana previa → vol 300

  const r = await getWeeklySummary('g1', NOW_WED);
  expect(r.sesiones).toBe(2);
  expect(r.volumenSemana).toBe(900);
  expect(r.volumenSemanaPrevia).toBe(300);
  expect(r.deltaPct).toBe(200); // (900-300)/300*100
});

it('getWeeklySummary deltaPct null sin semana previa', async () => {
  await db.workoutSessions.put({ id: 'wx-a', userId: null, gymId: 'g9', fecha: NOW_WED, updatedAt: 1, deletedAt: null });
  const le = { id: 'wx-le', sessionId: 'wx-a', exerciseId: 'wx-ex', orden: 0, updatedAt: 1, deletedAt: null };
  await db.loggedExercises.put(le);
  await db.loggedSets.put({ id: 'wx-set', loggedExerciseId: 'wx-le', orden: 0, peso: 20, reps: 5, updatedAt: 1, deletedAt: null });
  const r = await getWeeklySummary('g9', NOW_WED);
  expect(r.sesiones).toBe(1);
  expect(r.deltaPct).toBeNull();
});
```
(Ensure `db` and `getWeeklySummary` are imported. Use gym ids `g1`/`g9` unique to these tests to avoid cross-test interference.)

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/repositories/stats.test.ts -t "getWeeklySummary"`
Expected: FAIL — not exported.

- [ ] **Step 3: Implement** — add to `lib/repositories/stats.ts`:

```ts
export interface WeeklySummary {
  sesiones: number;
  volumenSemana: number;
  volumenSemanaPrevia: number;
  deltaPct: number | null;
}

/** Resumen de la semana ISO actual (lunes) vs la previa. `now` inyectable para tests. */
export async function getWeeklySummary(gymId?: string | null, now: number = Date.now()): Promise<WeeklySummary> {
  const inicioActual = inicioSemana(now);
  const inicioPrevia = inicioSemana(inicioActual - 1);
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId)
    .filter((s) => s.fecha >= inicioPrevia);
  const sessionIds = new Set(sessions.map((s) => s.id));
  const les = sessionIds.size === 0
    ? []
    : activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  const volBySession = new Map<string, number>();
  for (const le of les) {
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    const vol = sets.reduce((acc, s) => acc + s.peso * s.reps, 0);
    volBySession.set(le.sessionId, (volBySession.get(le.sessionId) ?? 0) + vol);
  }
  let sesiones = 0, volumenSemana = 0, volumenSemanaPrevia = 0;
  for (const s of sessions) {
    const vol = volBySession.get(s.id) ?? 0;
    if (s.fecha >= inicioActual) { sesiones++; volumenSemana += vol; }
    else { volumenSemanaPrevia += vol; }
  }
  const deltaPct = volumenSemanaPrevia > 0
    ? Math.round(((volumenSemana - volumenSemanaPrevia) / volumenSemanaPrevia) * 100)
    : null;
  return { sesiones, volumenSemana, volumenSemanaPrevia, deltaPct };
}
```

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run lib/repositories/stats.test.ts -t "getWeeklySummary"`
Expected: PASS (2). Also `npx tsc --noEmit` clean.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/stats.ts lib/repositories/stats.test.ts
git commit -m "feat(insights): getWeeklySummary (sesiones + volumen semana vs previa)"
```

---

## Task 2: `getPRsThisWeek` en stats.ts

**Files:**
- Modify: `lib/repositories/stats.ts`
- Modify: `lib/repositories/stats.test.ts`

- [ ] **Step 0: READ FIRST**

Confirm `estimar1RM(peso, reps)` is exported at the top of `stats.ts`, and the private `setsDeEjercicio(exerciseId, gymId?, excludeSessionId?)` returns `{ set: LoggedSet; fecha: number }[]`.

- [ ] **Step 1: Write the failing test** — append to `lib/repositories/stats.test.ts` (reuse `NOW_WED`/`DAY` from Task 1's tests — they're in the same file scope):

```ts
import { getPRsThisWeek } from '@/lib/repositories/stats';

it('getPRsThisWeek detecta PR de peso esta semana y excluye sin-mejora / sin-histórico', async () => {
  const seedSet = async (sid: string, exerciseId: string, fecha: number, peso: number) => {
    await db.workoutSessions.put({ id: sid, userId: null, gymId: 'gpr', fecha, updatedAt: 1, deletedAt: null });
    const le = { id: `${sid}-le`, sessionId: sid, exerciseId, orden: 0, updatedAt: 1, deletedAt: null };
    await db.loggedExercises.put(le);
    await db.loggedSets.put({ id: `${sid}-set`, loggedExerciseId: le.id, orden: 0, peso, reps: 5, updatedAt: 1, deletedAt: null });
  };
  await db.exercises.put({ id: 'pr-sube', userId: null, nombre: 'Sube', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null });
  await db.exercises.put({ id: 'pr-baja', userId: null, nombre: 'Baja', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null });
  await db.exercises.put({ id: 'pr-nuevo', userId: null, nombre: 'Nuevo', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null });

  await seedSet('pr-s1', 'pr-sube', NOW_WED - 7 * DAY, 60);  // previo
  await seedSet('pr-s2', 'pr-sube', NOW_WED, 65);            // esta semana → PR peso
  await seedSet('pr-b1', 'pr-baja', NOW_WED - 7 * DAY, 80);  // previo
  await seedSet('pr-b2', 'pr-baja', NOW_WED, 70);            // esta semana, NO supera → no PR
  await seedSet('pr-n1', 'pr-nuevo', NOW_WED, 50);           // solo esta semana, sin histórico → no PR

  const prs = await getPRsThisWeek('gpr', NOW_WED);
  const ids = prs.map((p) => p.exerciseId);
  expect(ids).toContain('pr-sube');
  expect(ids).not.toContain('pr-baja');
  expect(ids).not.toContain('pr-nuevo');
  expect(prs.find((p) => p.exerciseId === 'pr-sube')?.tipo).toBe('peso');
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/repositories/stats.test.ts -t "getPRsThisWeek"`
Expected: FAIL — not exported.

- [ ] **Step 3: Implement** — add to `lib/repositories/stats.ts`:

```ts
export interface PRSemana {
  exerciseId: string;
  nombre: string;
  tipo: 'peso' | '1rm';
}

/** Ejercicios que batieron su récord (peso o 1RM estimado) esta semana respecto a su histórico previo. */
export async function getPRsThisWeek(gymId?: string | null, now: number = Date.now()): Promise<PRSemana[]> {
  const inicio = inicioSemana(now);
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId)
    .filter((s) => s.fecha >= inicio);
  const sessionIds = new Set(sessions.map((s) => s.id));
  if (sessionIds.size === 0) return [];
  const lesWeek = activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  const exerciseIds = [...new Set(lesWeek.map((le) => le.exerciseId))];
  const exercises = await db.exercises.bulkGet(exerciseIds);
  const nombreBy = new Map<string, string>();
  for (const e of exercises) if (e) nombreBy.set(e.id, e.nombre);
  const out: PRSemana[] = [];
  for (const exerciseId of exerciseIds) {
    const data = await setsDeEjercicio(exerciseId, gymId);
    const week = data.filter((d) => d.fecha >= inicio);
    const before = data.filter((d) => d.fecha < inicio);
    if (week.length === 0 || before.length === 0) continue; // sin histórico previo → no es "batir"
    const maxPesoWeek = Math.max(...week.map((d) => d.set.peso));
    const maxPesoBefore = Math.max(...before.map((d) => d.set.peso));
    if (maxPesoWeek > maxPesoBefore) {
      out.push({ exerciseId, nombre: nombreBy.get(exerciseId) ?? '—', tipo: 'peso' });
      continue;
    }
    const max1rmWeek = Math.max(...week.map((d) => estimar1RM(d.set.peso, d.set.reps)));
    const max1rmBefore = Math.max(...before.map((d) => estimar1RM(d.set.peso, d.set.reps)));
    if (max1rmWeek > max1rmBefore) {
      out.push({ exerciseId, nombre: nombreBy.get(exerciseId) ?? '—', tipo: '1rm' });
    }
  }
  return out;
}
```

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run lib/repositories/stats.test.ts`
Expected: PASS (new + existing). Also `npx tsc --noEmit` clean.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/stats.ts lib/repositories/stats.test.ts
git commit -m "feat(insights): getPRsThisWeek (PRs de peso/1RM batidos esta semana)"
```

---

## Task 3: Mini-resumen en Home

**Files:**
- Create: `components/weekly-digest-mini.tsx`
- Modify: `app/page.tsx`

> UI sobre funciones ya testeadas; verificación por tsc/lint.

- [ ] **Step 1: Create the component** — `components/weekly-digest-mini.tsx`:

```tsx
'use client';

import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { getWeeklySummary, getPRsThisWeek } from '@/lib/repositories/stats';
import { useSetting } from '@/lib/use-setting';

export function WeeklyDigestMini() {
  const resumen = useLiveQuery(() => getWeeklySummary(), []);
  const prs = useLiveQuery(() => getPRsThisWeek(), []);
  const [objetivo] = useSetting<number>('objetivoSemanal', 3);
  if (!resumen) return null;
  const nPRs = prs?.length ?? 0;
  const delta = resumen.deltaPct;
  return (
    <Link href="/progreso" className="brutal-box block px-3 py-2.5">
      <p className="label-mono text-[10px] text-muted-foreground">Esta semana</p>
      <p className="font-medium">
        {resumen.sesiones}/{objetivo} sesiones · {nPRs} {nPRs === 1 ? 'PR' : 'PRs'}
        {delta != null && ` · vol ${delta >= 0 ? '▲' : '▼'}${Math.abs(delta)}%`}
      </p>
    </Link>
  );
}
```

- [ ] **Step 2: Wire into Home** — in `app/page.tsx`, import and render it between the header `<div>` and `<StartWorkout />`:

```tsx
import { WeeklyDigestMini } from '@/components/weekly-digest-mini';
```
```tsx
      <WeeklyDigestMini />
      <StartWorkout />
```
(`app/page.tsx` is a server component; `WeeklyDigestMini` is a client component — importing and rendering it is fine.)

- [ ] **Step 3: Typecheck + lint**

Run: `npx tsc --noEmit && npx eslint components/weekly-digest-mini.tsx app/page.tsx`
Expected: clean. Also `npx vitest run` (nothing breaks).

- [ ] **Step 4: Commit**

```bash
git add components/weekly-digest-mini.tsx app/page.tsx
git commit -m "feat(insights): mini-resumen semanal en Home"
```

---

## Task 4: Resumen completo en Progreso

**Files:**
- Create: `components/weekly-digest.tsx`
- Modify: `app/progreso/page.tsx`

- [ ] **Step 0: READ FIRST**

Read `app/progreso/page.tsx` (the gym filter `const gymId = filtroAGymId(filtro)`, the section markup, and the top StatCards). Read `components/stat-card.tsx` (`StatCard({ valor, unidad, destacado })`).

- [ ] **Step 1: Create the component** — `components/weekly-digest.tsx`:

```tsx
'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { getWeeklySummary, getPRsThisWeek } from '@/lib/repositories/stats';
import { useSetting } from '@/lib/use-setting';
import { StatCard } from '@/components/stat-card';

export function WeeklyDigest({ gymId }: { gymId?: string }) {
  const resumen = useLiveQuery(() => getWeeklySummary(gymId), [gymId]);
  const prs = useLiveQuery(() => getPRsThisWeek(gymId), [gymId]);
  const [objetivo] = useSetting<number>('objetivoSemanal', 3);
  if (!resumen) return null;
  const delta = resumen.deltaPct;
  return (
    <div className="space-y-2">
      <div className="grid grid-cols-3 gap-2">
        <StatCard valor={`${resumen.sesiones}/${objetivo}`} unidad="sesiones" destacado />
        <StatCard valor={`${prs?.length ?? 0}`} unidad="PR semana" />
        <StatCard valor={delta == null ? '—' : `${delta >= 0 ? '▲' : '▼'}${Math.abs(delta)}%`} unidad="vol vs previa" />
      </div>
      {prs && prs.length > 0 && (
        <ul className="brutal-box divide-y-2 divide-foreground">
          {prs.map((p) => (
            <li key={p.exerciseId} className="flex items-center justify-between gap-2 px-3 py-2">
              <span className="font-medium">{p.nombre}</span>
              <span className="label-mono text-[10px] text-primary">PR {p.tipo === 'peso' ? 'peso' : '1RM'}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Wire into Progreso** — in `app/progreso/page.tsx`:

1. Add imports:
```ts
import { WeeklyDigest } from '@/components/weekly-digest';
```
2. Add a section near the TOP (after `<GymFilter />`/`<PeriodSelector />`, before the existing streak/summary StatCards), matching the section markup:
```tsx
<section className="space-y-2">
  <h2 className="label-mono text-[10px] text-muted-foreground">Esta semana</h2>
  <WeeklyDigest gymId={gymId} />
</section>
```
Adapt placement to the real structure (a labeled "Esta semana" section among the others).

- [ ] **Step 3: Typecheck + lint**

Run: `npx tsc --noEmit && npx eslint components/weekly-digest.tsx app/progreso/page.tsx`
Expected: clean. Also `npx vitest run`.

- [ ] **Step 4: Commit**

```bash
git add components/weekly-digest.tsx app/progreso/page.tsx
git commit -m "feat(insights): resumen semanal completo en Progreso (adherencia + PRs + tendencia)"
```

---

## Task 5: Ajuste "Objetivo semanal" en Ajustes

**Files:**
- Modify: `app/ajustes/page.tsx`

- [ ] **Step 0: READ FIRST**

Read `app/ajustes/page.tsx` — note it's a client component, the existing sections (incl. the "Progresión" section with uncontrolled `onBlur` number inputs), and that `useSetting` is the synced-settings hook from C0.

- [ ] **Step 1: Implement** — in `app/ajustes/page.tsx`:

1. Add imports (merge with existing):
```ts
import { useSetting } from '@/lib/use-setting';
```
2. In the component:
```ts
const [objetivoSemanal, setObjetivoSemanal] = useSetting<number>('objetivoSemanal', 3);
```
3. Add a section (place it near the "Progresión" section, consistent markup). Use an uncontrolled `defaultValue` + `onBlur` (same anti-trap pattern as the increments inputs), with `key` so it re-syncs on external change:
```tsx
<section className="space-y-3">
  <h2 className="label-mono text-[11px] text-muted-foreground">Objetivos</h2>
  <label className="brutal-box flex items-center justify-between gap-3 px-3 py-2.5">
    <span className="font-semibold">Sesiones objetivo por semana</span>
    <input
      type="number"
      min="1"
      step="1"
      key={objetivoSemanal}
      defaultValue={objetivoSemanal}
      className="w-20 border-2 border-input bg-card p-1 text-right text-sm tabular-nums"
      onBlur={(e) => {
        const n = Math.round(Number(e.target.value));
        if (e.target.value.trim() === '' || Number.isNaN(n) || n < 1) {
          e.target.value = String(objetivoSemanal);
          return;
        }
        setObjetivoSemanal(n);
      }}
    />
  </label>
</section>
```

- [ ] **Step 2: Typecheck + lint + suite**

Run: `npx tsc --noEmit && npx eslint app/ajustes/page.tsx && npx vitest run`
Expected: clean/green (there may be an ajustes page test; if it exists, ensure it still passes — the new section is additive).

- [ ] **Step 3: Commit**

```bash
git add app/ajustes/page.tsx
git commit -m "feat(insights): ajuste objetivo de sesiones por semana (sincronizado)"
```

---

## Task 6: Verificación final

- [ ] **Step 1: Full suite**

Run: `npx vitest run`
Expected: todo verde (getWeeklySummary + getPRsThisWeek + previos).

- [ ] **Step 2: Types + lint**

Run: `npx tsc --noEmit && npm run lint`
Expected: sin errores.

- [ ] **Step 3: Build**

Run: `npm run build`
Expected: build OK (`--webpack`).

---

## Self-Review (hecho)

**Cobertura del spec (módulo 2):**
- `getWeeklySummary(gymId?)` → `{ sesiones, volumenSemana, volumenSemanaPrevia, deltaPct }`, semana ISO (lunes), deltaPct null sin previa → Task 1. ✓
- `getPRsThisWeek(gymId?)` → ejercicios que baten máx peso o mejor 1RM esta semana vs histórico previo → Task 2. ✓
- Objetivo semanal vía `useSetting('objetivoSemanal', 3)` (sincronizado, C0); adherencia = sesiones/objetivo → Tasks 3, 4, 5. ✓
- Home: `WeeklyDigestMini` `Esta semana · X/obj sesiones · N PR · vol ▲/▼%` con enlace a Progreso → Task 3. ✓
- Progreso: `WeeklyDigest` completo (métricas + lista de PRs) → Task 4. ✓
- Tests: weekly summary (semana/previa/delta/null), PRs (peso/no-supera/sin-histórico) → Tasks 1, 2. ✓

**Sin placeholders:** código completo en cada paso.

**Consistencia de tipos:** `WeeklySummary`/`PRSemana` definidos en stats.ts (Tasks 1-2) y consumidos en los componentes (Tasks 3-4). `getWeeklySummary(gymId?, now?)` y `getPRsThisWeek(gymId?, now?)` con la misma firma (now inyectable). `useSetting<number>('objetivoSemanal', 3)` con la misma clave en mini, completo y Ajustes. StatCard usa `{valor, unidad, destacado}` real. ✓

**Decisión consciente:** `getPRsThisWeek` NO cuenta como PR el primer registro de un ejercicio (sin histórico previo no hay récord que batir); evita inflar el digest con ejercicios nuevos. Home muestra datos globales (sin gym), Progreso respeta el filtro de gym.
