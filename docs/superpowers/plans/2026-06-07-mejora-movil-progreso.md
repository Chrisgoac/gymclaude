# Mejora visual móvil + Progreso con charts — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rediseñar el navbar inferior (5 pestañas con icono) y convertir la página de Progreso en un dashboard con charts (resumen, volumen semanal, por-ejercicio mejorado, balance muscular) filtrable por periodo.

**Architecture:** Solo UI + lectura. Se añaden 2 funciones de lectura a `lib/repositories/stats.ts` y un parámetro `sinceTs` a una existente; un helper puro `lib/period.ts`; varios componentes presentacionales en `components/`; y se recompone `app/progreso/page.tsx`. No se toca el schema de Dexie, ni el sync, ni la escritura de datos.

**Tech Stack:** Next.js 16 (App Router, client components), React 19, Tailwind v4 (tokens OKLch en `globals.css`), Recharts 3 (ya instalado), Lucide React (ya instalado), Vitest + Testing Library + fake-indexeddb.

**Spec de referencia:** `docs/superpowers/specs/2026-06-07-mejora-movil-progreso-design.md`

**Comandos de test:**
- Un archivo: `npx vitest run <ruta>`
- Todo: `npm test`

**Convenciones del repo a respetar:**
- Estética "Brutalist Iron": borde `border-2 border-foreground`, radio 0, sombras duras (`brutal-shadow-sm` o `box-shadow: Npx Npx 0 0 var(--color-foreground)`), etiquetas con clase `label-mono`, números grandes con fuente display (`font-[family-name:var(--font-display)]`).
- Filtro de gimnasio: `useGymFilter()` + `filtroAGymId(filtro)` → `gymId: string | undefined`.
- Tests de datos: patrón `beforeEach` que limpia tablas + helper `sesionCon(fecha, exerciseId, series)` (ver `lib/repositories/stats.test.ts`).

---

## File Structure

| Archivo | Acción | Responsabilidad |
|---|---|---|
| `lib/period.ts` | Crear | Tipo `Periodo` + `periodoASinceTs()` (puro) |
| `lib/period.test.ts` | Crear | Tests del helper de periodo |
| `lib/repositories/stats.ts` | Modificar | `getPeriodSummary`, `getWeeklyVolume`, `sinceTs` en `getExerciseProgress` |
| `lib/repositories/stats.test.ts` | Modificar | Tests de las funciones nuevas/modificada |
| `components/bottom-nav.tsx` | Modificar | 5 pestañas con icono + etiqueta |
| `components/bottom-nav.test.tsx` | Modificar | Refleja 5 pestañas (sin Ajustes) |
| `components/auth-header.tsx` | Modificar | Icono ⚙ → `/ajustes` |
| `components/period-selector.tsx` | Crear | Segmented control de periodo |
| `components/period-selector.test.tsx` | Crear | Test de render + selección |
| `components/stat-card.tsx` | Crear | Tarjeta de estadística (número + unidad) |
| `components/weekly-volume-chart.tsx` | Crear | BarChart de volumen semanal |
| `components/muscle-balance.tsx` | Crear | Barras horizontales de volumen por músculo |
| `components/muscle-balance.test.tsx` | Crear | Test de render con datos |
| `components/exercise-chart.tsx` | Modificar | Prop `metric` (1rm/peso/volumen) |
| `components/exercise-progress.tsx` | Modificar | PR cards reestilizadas + toggle métrica + `sinceTs` |
| `app/progreso/page.tsx` | Reescribir | Compone los 5 módulos |

---

## Task 1: Helper de periodo (`lib/period.ts`)

**Files:**
- Create: `lib/period.ts`
- Test: `lib/period.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// lib/period.test.ts
import { describe, it, expect } from 'vitest';
import { periodoASinceTs, PERIODOS, type Periodo } from '@/lib/period';

const DAY = 24 * 60 * 60 * 1000;
const AHORA = 1_000_000 * DAY; // base determinista

describe('periodoASinceTs', () => {
  it('"todo" devuelve 0 (sin límite inferior)', () => {
    expect(periodoASinceTs('todo', AHORA)).toBe(0);
  });
  it('"4s" resta 28 días', () => {
    expect(periodoASinceTs('4s', AHORA)).toBe(AHORA - 28 * DAY);
  });
  it('"3m" resta 90 días', () => {
    expect(periodoASinceTs('3m', AHORA)).toBe(AHORA - 90 * DAY);
  });
  it('"ano" resta 365 días', () => {
    expect(periodoASinceTs('ano', AHORA)).toBe(AHORA - 365 * DAY);
  });
  it('PERIODOS lista las 4 opciones en orden', () => {
    expect(PERIODOS.map((p) => p.id)).toEqual<Periodo[]>(['4s', '3m', 'ano', 'todo']);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run lib/period.test.ts`
Expected: FAIL — "Cannot find module '@/lib/period'".

- [ ] **Step 3: Write minimal implementation**

```ts
// lib/period.ts
export type Periodo = '4s' | '3m' | 'ano' | 'todo';

export const PERIODOS: { id: Periodo; label: string }[] = [
  { id: '4s', label: '4 sem' },
  { id: '3m', label: '3 meses' },
  { id: 'ano', label: 'Año' },
  { id: 'todo', label: 'Todo' },
];

const DAY = 24 * 60 * 60 * 1000;
const DIAS: Record<Periodo, number> = { '4s': 28, '3m': 90, ano: 365, todo: 0 };

/** Devuelve el timestamp de inicio del periodo. 0 = sin límite ("Todo"). */
export function periodoASinceTs(p: Periodo, ahora: number = Date.now()): number {
  return DIAS[p] === 0 ? 0 : ahora - DIAS[p] * DAY;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run lib/period.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add lib/period.ts lib/period.test.ts
git commit -m "feat(progreso): helper periodoASinceTs"
```

---

## Task 2: `getPeriodSummary` en stats

**Files:**
- Modify: `lib/repositories/stats.ts`
- Test: `lib/repositories/stats.test.ts`

- [ ] **Step 1: Write the failing test** (añadir al final de `stats.test.ts`; e importar `getPeriodSummary` en el bloque de imports superior)

```ts
describe('getPeriodSummary', () => {
  it('cuenta sesiones y suma volumen del periodo', async () => {
    await sesionCon(1 * DAY, 'seed-press-banca', [[60, 10]]); // 600
    await sesionCon(3 * DAY, 'seed-sentadilla', [[100, 5]]);  // 500
    const r = await getPeriodSummary(0);
    expect(r.sesiones).toBe(2);
    expect(r.volumen).toBe(1100);
  });
  it('respeta sinceTs', async () => {
    await sesionCon(1 * DAY, 'seed-press-banca', [[60, 10]]);
    await sesionCon(5 * DAY, 'seed-sentadilla', [[100, 5]]);
    const r = await getPeriodSummary(3 * DAY);
    expect(r.sesiones).toBe(1);
    expect(r.volumen).toBe(500);
  });
  it('respeta el filtro de gimnasio', async () => {
    const a = await startSession({ gymId: 'gymA' });
    const le = await addLoggedExercise(a.id, 'seed-sentadilla');
    await addSet(le.id, { peso: 100, reps: 5 });
    await startSession({ gymId: 'gymB' });
    const r = await getPeriodSummary(0, 'gymA');
    expect(r.sesiones).toBe(1);
    expect(r.volumen).toBe(500);
  });
});
```

Y en el import superior del test añade `getPeriodSummary`:

```ts
import {
  estimar1RM, getExerciseProgress, getExercisePRs, getVolumeByMuscle,
  listSessionSummaries, getCurrentStreakDays, getPeriodSummary,
} from '@/lib/repositories/stats';
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run lib/repositories/stats.test.ts`
Expected: FAIL — `getPeriodSummary is not a function` / import sin export.

- [ ] **Step 3: Write minimal implementation** (añadir a `lib/repositories/stats.ts`, tras `listSessionSummaries`)

```ts
export interface PeriodSummary {
  sesiones: number;
  volumen: number;
}

export async function getPeriodSummary(sinceTs = 0, gymId?: string | null): Promise<PeriodSummary> {
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => s.fecha >= sinceTs)
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId);
  if (sessions.length === 0) return { sesiones: 0, volumen: 0 };
  const sessionIds = new Set(sessions.map((s) => s.id));
  const les = activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  let volumen = 0;
  for (const le of les) {
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    volumen += sets.reduce((acc, s) => acc + s.peso * s.reps, 0);
  }
  return { sesiones: sessions.length, volumen };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run lib/repositories/stats.test.ts`
Expected: PASS (incluye los 3 nuevos).

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/stats.ts lib/repositories/stats.test.ts
git commit -m "feat(stats): getPeriodSummary (sesiones + volumen del periodo)"
```

---

## Task 3: `getWeeklyVolume` en stats

**Files:**
- Modify: `lib/repositories/stats.ts`
- Test: `lib/repositories/stats.test.ts`

- [ ] **Step 1: Write the failing test** (añadir a `stats.test.ts`; añadir `getWeeklyVolume` al import superior)

```ts
describe('getWeeklyVolume', () => {
  it('agrupa el volumen por semana (lunes) y ordena ascendente', async () => {
    // 2021-01-04 = lunes. Dos sesiones esa semana + una la semana siguiente.
    const lunes = new Date(2021, 0, 4).getTime();
    const miercoles = new Date(2021, 0, 6).getTime();
    const lunesSig = new Date(2021, 0, 11).getTime();
    await sesionCon(lunes, 'seed-press-banca', [[60, 10]]);      // 600
    await sesionCon(miercoles, 'seed-sentadilla', [[100, 5]]);   // 500
    await sesionCon(lunesSig, 'seed-press-banca', [[70, 10]]);   // 700

    const semanas = await getWeeklyVolume(0);
    expect(semanas).toHaveLength(2);
    expect(semanas[0].semanaInicioTs).toBe(new Date(2021, 0, 4).getTime());
    expect(semanas[0].volumen).toBe(1100);
    expect(semanas[1].volumen).toBe(700);
  });
  it('respeta sinceTs y el gimnasio', async () => {
    const a = await startSession({ gymId: 'gymA' });
    await db.workoutSessions.update(a.id, { fecha: new Date(2021, 0, 4).getTime() });
    const le = await addLoggedExercise(a.id, 'seed-sentadilla');
    await addSet(le.id, { peso: 100, reps: 5 });
    expect(await getWeeklyVolume(0, 'gymB')).toHaveLength(0);
  });
});
```

Import superior actualizado:

```ts
import {
  estimar1RM, getExerciseProgress, getExercisePRs, getVolumeByMuscle,
  listSessionSummaries, getCurrentStreakDays, getPeriodSummary, getWeeklyVolume,
} from '@/lib/repositories/stats';
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run lib/repositories/stats.test.ts`
Expected: FAIL — `getWeeklyVolume is not a function`.

- [ ] **Step 3: Write minimal implementation** (añadir a `lib/repositories/stats.ts`)

```ts
export interface WeeklyVolumePoint {
  semanaInicioTs: number;
  volumen: number;
}

/** Lunes 00:00 (hora local) de la semana que contiene ts. */
function inicioSemana(ts: number): number {
  const d = new Date(ts);
  d.setHours(0, 0, 0, 0);
  const dow = (d.getDay() + 6) % 7; // 0 = lunes
  d.setDate(d.getDate() - dow);
  return d.getTime();
}

export async function getWeeklyVolume(sinceTs = 0, gymId?: string | null): Promise<WeeklyVolumePoint[]> {
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => s.fecha >= sinceTs)
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId);
  if (sessions.length === 0) return [];
  const sessionIds = new Set(sessions.map((s) => s.id));
  const les = activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  const volBySession = new Map<string, number>();
  for (const le of les) {
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    const vol = sets.reduce((acc, s) => acc + s.peso * s.reps, 0);
    volBySession.set(le.sessionId, (volBySession.get(le.sessionId) ?? 0) + vol);
  }
  const byWeek = new Map<number, number>();
  for (const s of sessions) {
    const semana = inicioSemana(s.fecha);
    byWeek.set(semana, (byWeek.get(semana) ?? 0) + (volBySession.get(s.id) ?? 0));
  }
  return [...byWeek.entries()]
    .map(([semanaInicioTs, volumen]) => ({ semanaInicioTs, volumen }))
    .sort((a, b) => a.semanaInicioTs - b.semanaInicioTs);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run lib/repositories/stats.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/stats.ts lib/repositories/stats.test.ts
git commit -m "feat(stats): getWeeklyVolume (volumen agrupado por semana)"
```

---

## Task 4: `sinceTs` en `getExerciseProgress`

**Files:**
- Modify: `lib/repositories/stats.ts:39-57`
- Test: `lib/repositories/stats.test.ts`

- [ ] **Step 1: Write the failing test** (añadir dentro del `describe('getExerciseProgress', ...)` existente)

```ts
  it('filtra los puntos por sinceTs', async () => {
    await sesionCon(2 * DAY, 'seed-press-banca', [[60, 8]]);
    await sesionCon(5 * DAY, 'seed-press-banca', [[65, 8]]);
    const prog = await getExerciseProgress('seed-press-banca', undefined, 3 * DAY);
    expect(prog.map((p) => p.fecha)).toEqual([5 * DAY]);
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run lib/repositories/stats.test.ts -t "filtra los puntos por sinceTs"`
Expected: FAIL — devuelve 2 puntos (param ignorado) en vez de 1.

- [ ] **Step 3: Write minimal implementation** — modificar la firma y filtrar puntos. Reemplaza la cabecera y el `return` de `getExerciseProgress`:

```ts
export async function getExerciseProgress(
  exerciseId: string,
  gymId?: string | null,
  sinceTs = 0,
): Promise<ExerciseProgressPoint[]> {
  const data = await setsDeEjercicio(exerciseId, gymId);
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
  return points.filter((p) => p.fecha >= sinceTs).sort((a, b) => a.fecha - b.fecha);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run lib/repositories/stats.test.ts`
Expected: PASS (incluye el nuevo y los previos sin romper).

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/stats.ts lib/repositories/stats.test.ts
git commit -m "feat(stats): getExerciseProgress acepta sinceTs"
```

---

## Task 5: Navbar — 5 pestañas con icono

**Files:**
- Modify: `components/bottom-nav.tsx`
- Test: `components/bottom-nav.test.tsx`

- [ ] **Step 1: Update the test** — reemplaza el contenido de `components/bottom-nav.test.tsx`:

```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { usePathname } from 'next/navigation';
import { BottomNav } from '@/components/bottom-nav';

vi.mock('next/navigation', () => ({
  usePathname: vi.fn(),
}));

const mockedUsePathname = vi.mocked(usePathname);

describe('BottomNav', () => {
  it('muestra las cinco pestañas y NO incluye Ajustes', () => {
    mockedUsePathname.mockReturnValue('/');
    render(<BottomNav />);
    expect(screen.getByText('Entrenar')).toBeInTheDocument();
    expect(screen.getByText('Rutinas')).toBeInTheDocument();
    expect(screen.getByText('Ejercicios')).toBeInTheDocument();
    expect(screen.getByText('Progreso')).toBeInTheDocument();
    expect(screen.getByText('Historial')).toBeInTheDocument();
    expect(screen.queryByText('Ajustes')).not.toBeInTheDocument();
  });

  it('marca Ejercicios como activa en su ruta y subrutas', () => {
    mockedUsePathname.mockReturnValue('/ejercicios/nuevo');
    render(<BottomNav />);
    expect(screen.getByText('Ejercicios').closest('a')!.className).toContain('text-primary');
  });

  it('marca como activa la pestaña de la ruta actual', () => {
    mockedUsePathname.mockReturnValue('/rutinas');
    render(<BottomNav />);
    expect(screen.getByText('Rutinas').closest('a')!.className).toContain('text-primary');
    expect(screen.getByText('Entrenar').closest('a')!.className).toContain('text-muted-foreground');
  });

  it('marca Entrenar como activa en las subrutas del registro', () => {
    mockedUsePathname.mockReturnValue('/entrenar/abc123');
    render(<BottomNav />);
    expect(screen.getByText('Entrenar').closest('a')!.className).toContain('text-primary');
  });
});
```

> Nota: ahora la clase activa vive en el `<a>` (Link), no en el `<span>` de la etiqueta; por eso el test usa `.closest('a')`.

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run components/bottom-nav.test.tsx`
Expected: FAIL — "Ajustes" sigue presente y/o estructura del icono aún no existe.

- [ ] **Step 3: Implement** — reemplaza `components/bottom-nav.tsx`:

```tsx
'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Dumbbell, ClipboardList, LayoutGrid, TrendingUp, History, type LucideIcon } from 'lucide-react';

const TABS: { href: string; label: string; Icon: LucideIcon }[] = [
  { href: '/', label: 'Entrenar', Icon: Dumbbell },
  { href: '/rutinas', label: 'Rutinas', Icon: ClipboardList },
  { href: '/ejercicios', label: 'Ejercicios', Icon: LayoutGrid },
  { href: '/progreso', label: 'Progreso', Icon: TrendingUp },
  { href: '/historial', label: 'Historial', Icon: History },
];

export function BottomNav() {
  const pathname = usePathname();
  return (
    <nav className="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t-2 border-foreground bg-card pb-[env(safe-area-inset-bottom)]">
      {TABS.map(({ href, label, Icon }) => {
        // La pestaña Entrenar ('/') cubre también las subrutas del registro (/entrenar/...).
        const active =
          href === '/'
            ? pathname === '/' || pathname.startsWith('/entrenar')
            : pathname.startsWith(href);
        return (
          <Link
            key={href}
            href={href}
            className={`label-mono relative flex flex-col items-center gap-1 py-2.5 text-center text-[10px] transition-colors [&:not(:first-child)]:border-l-2 [&:not(:first-child)]:border-foreground ${
              active
                ? 'bg-primary text-primary-foreground font-bold'
                : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            {active && (
              <span className="absolute inset-x-0 top-0 h-1 bg-foreground" aria-hidden="true" />
            )}
            <Icon className="size-5" strokeWidth={2} aria-hidden="true" />
            <span className="leading-none">{label}</span>
          </Link>
        );
      })}
    </nav>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run components/bottom-nav.test.tsx`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add components/bottom-nav.tsx components/bottom-nav.test.tsx
git commit -m "feat(nav): 5 pestañas con icono + etiqueta, fuera Ajustes"
```

---

## Task 6: Ajustes → icono ⚙ en la cabecera

**Files:**
- Modify: `components/auth-header.tsx`

- [ ] **Step 1: Implement** — en `components/auth-header.tsx`, añade el import y el enlace dentro del `<div className="flex items-center gap-3">`, antes de `<SyncProvider />`.

Import nuevo (arriba):

```tsx
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Settings } from 'lucide-react';
```

Marca el componente como cliente (si no lo está ya tiene `'use client';` en la línea 1 — lo está). Dentro de la función, antes del `return`:

```tsx
  const pathname = usePathname();
  const ajustesActivo = pathname.startsWith('/ajustes');
```

Y en el contenedor de la derecha, como primer hijo:

```tsx
          <Link
            href="/ajustes"
            aria-label="Ajustes"
            className={`grid size-8 place-items-center border-2 border-foreground transition-transform active:translate-x-[1px] active:translate-y-[1px] ${
              ajustesActivo ? 'bg-primary text-primary-foreground' : 'bg-card text-foreground'
            }`}
          >
            <Settings className="size-4" strokeWidth={2} aria-hidden="true" />
          </Link>
```

- [ ] **Step 2: Typecheck/build sanity**

Run: `npx tsc --noEmit`
Expected: sin errores nuevos en `auth-header.tsx`.

- [ ] **Step 3: Commit**

```bash
git add components/auth-header.tsx
git commit -m "feat(nav): acceso a Ajustes con icono en la cabecera"
```

---

## Task 7: Componente `PeriodSelector`

**Files:**
- Create: `components/period-selector.tsx`
- Test: `components/period-selector.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// components/period-selector.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { PeriodSelector } from '@/components/period-selector';

describe('PeriodSelector', () => {
  it('muestra las 4 opciones y marca la activa', () => {
    render(<PeriodSelector value="4s" onChange={() => {}} />);
    expect(screen.getByText('4 sem').className).toContain('bg-primary');
    expect(screen.getByText('Todo')).toBeInTheDocument();
  });
  it('llama onChange con el id al pulsar', () => {
    const onChange = vi.fn();
    render(<PeriodSelector value="4s" onChange={onChange} />);
    fireEvent.click(screen.getByText('3 meses'));
    expect(onChange).toHaveBeenCalledWith('3m');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run components/period-selector.test.tsx`
Expected: FAIL — módulo inexistente.

- [ ] **Step 3: Implement**

```tsx
// components/period-selector.tsx
'use client';

import { PERIODOS, type Periodo } from '@/lib/period';

export function PeriodSelector({ value, onChange }: { value: Periodo; onChange: (p: Periodo) => void }) {
  return (
    <div className="flex border-2 border-foreground">
      {PERIODOS.map(({ id, label }, i) => (
        <button
          key={id}
          onClick={() => onChange(id)}
          className={`label-mono flex-1 py-1.5 text-center text-[10px] transition-colors ${
            i > 0 ? 'border-l-2 border-foreground' : ''
          } ${value === id ? 'bg-primary text-primary-foreground font-bold' : 'bg-card text-muted-foreground'}`}
        >
          {label}
        </button>
      ))}
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run components/period-selector.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add components/period-selector.tsx components/period-selector.test.tsx
git commit -m "feat(progreso): PeriodSelector (segmented control de periodo)"
```

---

## Task 8: Componente `StatCard`

**Files:**
- Create: `components/stat-card.tsx`

- [ ] **Step 1: Implement** (componente presentacional simple; sin test dedicado — se valida al integrar)

```tsx
// components/stat-card.tsx
export function StatCard({ valor, unidad, destacado = false }: { valor: string; unidad: string; destacado?: boolean }) {
  return (
    <div className="border-2 border-foreground bg-card p-2.5 brutal-shadow-sm">
      <p className={`font-[family-name:var(--font-display)] text-3xl leading-none ${destacado ? 'text-primary' : ''}`}>
        {valor}
      </p>
      <p className="label-mono mt-1 text-[9px] text-muted-foreground">{unidad}</p>
    </div>
  );
}
```

- [ ] **Step 2: Typecheck**

Run: `npx tsc --noEmit`
Expected: sin errores.

- [ ] **Step 3: Commit**

```bash
git add components/stat-card.tsx
git commit -m "feat(progreso): StatCard"
```

---

## Task 9: Componente `WeeklyVolumeChart`

**Files:**
- Create: `components/weekly-volume-chart.tsx`

- [ ] **Step 1: Implement** (mismo patrón visual que `ExerciseChart`; sin test de render — Recharts no mide en jsdom, igual que `exercise-chart.tsx` que tampoco tiene test)

```tsx
// components/weekly-volume-chart.tsx
'use client';

import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { WeeklyVolumePoint } from '@/lib/repositories/stats';

export function WeeklyVolumeChart({ data }: { data: WeeklyVolumePoint[] }) {
  const barras = data.map((p) => ({
    semana: new Date(p.semanaInicioTs).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit' }),
    volumen: p.volumen,
  }));
  return (
    <div className="h-48 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={barras} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
          <XAxis dataKey="semana" fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <YAxis fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <Tooltip
            cursor={{ fill: 'currentColor', fillOpacity: 0.08 }}
            contentStyle={{ border: '2px solid currentColor', borderRadius: 0, fontFamily: 'var(--font-mono)', fontSize: 12 }}
          />
          <Bar dataKey="volumen" className="text-primary" fill="currentColor" stroke="currentColor" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
```

- [ ] **Step 2: Typecheck**

Run: `npx tsc --noEmit`
Expected: sin errores.

- [ ] **Step 3: Commit**

```bash
git add components/weekly-volume-chart.tsx
git commit -m "feat(progreso): WeeklyVolumeChart (barras de volumen semanal)"
```

---

## Task 10: Componente `MuscleBalance`

**Files:**
- Create: `components/muscle-balance.tsx`
- Test: `components/muscle-balance.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// components/muscle-balance.test.tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MuscleBalance } from '@/components/muscle-balance';

describe('MuscleBalance', () => {
  it('muestra etiqueta y valor de cada grupo', () => {
    render(<MuscleBalance data={[
      { grupo: 'pecho', volumen: 1000 },
      { grupo: 'biceps', volumen: 250 },
    ]} />);
    expect(screen.getByText('Pecho')).toBeInTheDocument();
    expect(screen.getByText('Bíceps')).toBeInTheDocument();
    expect(screen.getByText('1000 kg·rep')).toBeInTheDocument();
  });
  it('muestra aviso cuando no hay datos', () => {
    render(<MuscleBalance data={[]} />);
    expect(screen.getByText(/Aún no hay volumen/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run components/muscle-balance.test.tsx`
Expected: FAIL — módulo inexistente.

- [ ] **Step 3: Implement**

```tsx
// components/muscle-balance.tsx
import type { VolumeByMuscle } from '@/lib/repositories/stats';
import { muscleGroupLabel } from '@/lib/labels';

export function MuscleBalance({ data }: { data: VolumeByMuscle[] }) {
  if (data.length === 0) return <p className="text-muted-foreground">Aún no hay volumen registrado.</p>;
  const max = Math.max(...data.map((d) => d.volumen), 1);
  return (
    <div className="space-y-2">
      {data.map((d) => (
        <div key={d.grupo}>
          <div className="label-mono mb-1 flex items-center justify-between text-[10px] text-muted-foreground">
            <span>{muscleGroupLabel[d.grupo]}</span>
            <span>{d.volumen} kg·rep</span>
          </div>
          <div className="h-3.5 border-2 border-foreground bg-card">
            <div className="h-full bg-primary" style={{ width: `${(d.volumen / max) * 100}%` }} />
          </div>
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run components/muscle-balance.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add components/muscle-balance.tsx components/muscle-balance.test.tsx
git commit -m "feat(progreso): MuscleBalance (barras horizontales por músculo)"
```

---

## Task 11: `ExerciseChart` con prop `metric` + toggle en `ExerciseProgress`

**Files:**
- Modify: `components/exercise-chart.tsx`
- Modify: `components/exercise-progress.tsx`

- [ ] **Step 1: Implement `ExerciseChart`** — reemplaza `components/exercise-chart.tsx`:

```tsx
'use client';

import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { ExerciseProgressPoint } from '@/lib/repositories/stats';

export type Metric = '1rm' | 'peso' | 'volumen';

const CAMPO: Record<Metric, keyof ExerciseProgressPoint> = {
  '1rm': 'mejor1RM',
  peso: 'maxPeso',
  volumen: 'volumen',
};

export function ExerciseChart({ data, metric }: { data: ExerciseProgressPoint[]; metric: Metric }) {
  const campo = CAMPO[metric];
  const puntos = data.map((p) => ({
    fecha: new Date(p.fecha).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit' }),
    valor: p[campo],
  }));
  return (
    <div className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={puntos} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
          <XAxis dataKey="fecha" fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <YAxis fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <Tooltip
            cursor={{ stroke: 'currentColor', strokeWidth: 1 }}
            contentStyle={{ border: '2px solid currentColor', borderRadius: 0, fontFamily: 'var(--font-mono)', fontSize: 12 }}
          />
          <Line
            type="stepAfter"
            dataKey="valor"
            stroke="currentColor"
            className="text-primary"
            strokeWidth={3}
            dot={{ r: 3, strokeWidth: 0, fill: 'currentColor' }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
```

- [ ] **Step 2: Implement `ExerciseProgress`** — reemplaza `components/exercise-progress.tsx`:

```tsx
'use client';

import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { getExerciseProgress, getExercisePRs } from '@/lib/repositories/stats';
import { ExerciseChart, type Metric } from '@/components/exercise-chart';

const METRICAS: { id: Metric; label: string }[] = [
  { id: '1rm', label: '1RM' },
  { id: 'peso', label: 'Peso máx' },
  { id: 'volumen', label: 'Volumen' },
];

export function ExerciseProgress({ exerciseId, gymId, sinceTs = 0 }: { exerciseId: string; gymId?: string; sinceTs?: number }) {
  const progreso = useLiveQuery(() => getExerciseProgress(exerciseId, gymId, sinceTs), [exerciseId, gymId, sinceTs]);
  const prs = useLiveQuery(() => getExercisePRs(exerciseId, gymId), [exerciseId, gymId]);
  const [metric, setMetric] = useState<Metric>('1rm');

  if (progreso === undefined || prs === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (prs === null) return <p className="text-muted-foreground">Sin datos todavía para este ejercicio.</p>;

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-2">
        <div className="border-2 border-foreground bg-card p-3 brutal-shadow-sm">
          <p className="label-mono text-[9px] text-muted-foreground">Máx. peso</p>
          <p className="font-[family-name:var(--font-display)] text-2xl leading-none">{prs.maxPeso} kg</p>
        </div>
        <div className="border-2 border-foreground bg-card p-3 brutal-shadow-sm">
          <p className="label-mono text-[9px] text-muted-foreground">Mejor 1RM est.</p>
          <p className="font-[family-name:var(--font-display)] text-2xl leading-none">{prs.mejor1RM} kg</p>
        </div>
      </div>

      <div className="flex border-2 border-foreground">
        {METRICAS.map(({ id, label }, i) => (
          <button
            key={id}
            onClick={() => setMetric(id)}
            className={`label-mono flex-1 py-1.5 text-center text-[10px] transition-colors ${
              i > 0 ? 'border-l-2 border-foreground' : ''
            } ${metric === id ? 'bg-primary text-primary-foreground font-bold' : 'bg-card text-muted-foreground'}`}
          >
            {label}
          </button>
        ))}
      </div>

      {progreso.length > 1 ? (
        <ExerciseChart data={progreso} metric={metric} />
      ) : (
        <p className="text-muted-foreground">Necesitas al menos 2 registros para ver la evolución.</p>
      )}
    </div>
  );
}
```

- [ ] **Step 3: Run the existing component test (no debe romper)**

Run: `npx vitest run components/exercise-progress.test.tsx`
Expected: PASS. Si el test existente afirmaba el render del chart con 1 solo punto, ajústalo al nuevo texto ("Necesitas al menos 2 registros…"). Lee el test antes de tocar y adapta solo lo necesario.

- [ ] **Step 4: Commit**

```bash
git add components/exercise-chart.tsx components/exercise-progress.tsx components/exercise-progress.test.tsx
git commit -m "feat(progreso): toggle de métrica (1RM/peso/volumen) en por-ejercicio"
```

---

## Task 12: Recomponer `app/progreso/page.tsx`

**Files:**
- Modify: `app/progreso/page.tsx`

- [ ] **Step 1: Implement** — reemplaza `app/progreso/page.tsx`:

```tsx
'use client';

import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { listExercises } from '@/lib/repositories/exercises';
import { getVolumeByMuscle, getPeriodSummary, getWeeklyVolume } from '@/lib/repositories/stats';
import { getCurrentStreakDays } from '@/lib/repositories/stats';
import { ExerciseProgress } from '@/components/exercise-progress';
import { WeeklyVolumeChart } from '@/components/weekly-volume-chart';
import { MuscleBalance } from '@/components/muscle-balance';
import { PeriodSelector } from '@/components/period-selector';
import { StatCard } from '@/components/stat-card';
import { GymFilter } from '@/components/gym-filter';
import { useGymFilter, filtroAGymId } from '@/lib/gym-filter';
import { periodoASinceTs, type Periodo } from '@/lib/period';

function formatoVolumen(kg: number): string {
  return kg >= 1000 ? `${(kg / 1000).toFixed(1)}t` : `${kg}`;
}

export default function ProgresoPage() {
  const ejercicios = useLiveQuery(() => listExercises(), []);
  const [filtro] = useGymFilter();
  const gymId = filtroAGymId(filtro);
  const [periodo, setPeriodo] = useState<Periodo>('4s');
  const sinceTs = periodoASinceTs(periodo);

  const racha = useLiveQuery(() => getCurrentStreakDays(), []);
  const resumen = useLiveQuery(() => getPeriodSummary(sinceTs, gymId), [sinceTs, gymId]);
  const semanal = useLiveQuery(() => getWeeklyVolume(sinceTs, gymId), [sinceTs, gymId]);
  const volumen = useLiveQuery(() => getVolumeByMuscle(sinceTs, gymId), [sinceTs, gymId]);
  const [seleccion, setSeleccion] = useState('');

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Progreso</h1>
      <GymFilter />
      <PeriodSelector value={periodo} onChange={setPeriodo} />

      <section className="grid grid-cols-3 gap-2">
        <StatCard valor={`${racha ?? 0}`} unidad="🔥 racha días" destacado />
        <StatCard valor={`${resumen?.sesiones ?? 0}`} unidad="sesiones" />
        <StatCard valor={formatoVolumen(resumen?.volumen ?? 0)} unidad="volumen" />
      </section>

      <section className="space-y-2">
        <h2 className="label-mono text-[10px] text-muted-foreground">Volumen semanal</h2>
        {(semanal ?? []).length === 0
          ? <p className="text-muted-foreground">Aún no hay sesiones en este periodo.</p>
          : <WeeklyVolumeChart data={semanal ?? []} />}
      </section>

      <section className="space-y-2">
        <label htmlFor="ejercicio" className="label-mono text-[10px] text-muted-foreground">
          Por ejercicio
        </label>
        <select
          id="ejercicio"
          className="w-full border-2 border-foreground bg-card p-2"
          value={seleccion}
          onChange={(e) => setSeleccion(e.target.value)}
        >
          <option value="">Elige un ejercicio…</option>
          {(ejercicios ?? []).map((e) => (
            <option key={e.id} value={e.id}>{e.nombre}</option>
          ))}
        </select>
        {seleccion && <ExerciseProgress exerciseId={seleccion} gymId={gymId} sinceTs={sinceTs} />}
      </section>

      <section className="space-y-2">
        <h2 className="label-mono text-[10px] text-muted-foreground">Balance muscular</h2>
        <MuscleBalance data={volumen ?? []} />
      </section>
    </div>
  );
}
```

> Nota de altura: combina los dos imports de `@/lib/repositories/stats` en una sola línea si tu linter lo prefiere.

- [ ] **Step 2: Typecheck + tests + build**

Run: `npx tsc --noEmit && npm test`
Expected: typecheck limpio; todos los tests PASS.

- [ ] **Step 3: Commit**

```bash
git add app/progreso/page.tsx
git commit -m "feat(progreso): dashboard con periodo, resumen, semanal y balance"
```

---

## Task 13: Verificación manual en móvil

**Files:** ninguno (verificación)

- [ ] **Step 1: Arrancar la app**

Run: `npm run dev`
Abrir en el navegador con viewport móvil (DevTools → ~390px).

- [ ] **Step 2: Comprobar navbar**
  - 5 pestañas con icono + etiqueta, sin desbordes ni texto cortado.
  - Pestaña activa resaltada en naranja con barra superior.
  - El icono ⚙ de la cabecera abre `/ajustes`.

- [ ] **Step 3: Comprobar Progreso** (con datos sembrados; si la BD está vacía, registra un par de entrenos)
  - Selector de periodo cambia las cifras de resumen, el chart semanal y el balance.
  - Resumen muestra racha / sesiones / volumen.
  - "Por ejercicio": el toggle 1RM/Peso máx/Volumen cambia la línea; PR cards visibles.
  - Balance muscular: barras horizontales proporcionales con etiqueta y valor.
  - El filtro de gimnasio sigue afectando a todo.

- [ ] **Step 4: Suite completa final**

Run: `npm test`
Expected: todo PASS.

---

## Self-Review (hecho)

- **Cobertura del spec:** navbar Opción A (Task 5-6) ✓; periodo (Task 1,7,12) ✓; resumen (Task 2,8,12) ✓; volumen semanal (Task 3,9,12) ✓; por-ejercicio mejorado (Task 4,11) ✓; balance muscular (Task 10,12) ✓; capa de datos (Task 2,3,4) ✓.
- **Sin placeholders:** todos los pasos con código real.
- **Consistencia de tipos:** `Periodo`/`periodoASinceTs` (lib/period), `PeriodSummary`/`WeeklyVolumePoint`/`Metric`, firma `getExerciseProgress(exerciseId, gymId?, sinceTs?)` usada igual en Task 4, 11 y 12. `WeeklyVolumeChart`/`MuscleBalance` reciben los tipos exportados por stats.
- **Riesgo conocido:** `components/exercise-progress.test.tsx` existe; Task 11 Step 3 obliga a leerlo y adaptar el caso de "1 solo punto" al nuevo mensaje. Charts (ExerciseChart/WeeklyVolumeChart) sin test de render, coherente con el repo.
