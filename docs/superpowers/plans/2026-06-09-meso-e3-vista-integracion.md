# Mesociclos E3 — Vista del mesociclo + integración (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pantalla `/mesociclo/[id]` (semana actual, progresión, días-rutina con "Empezar", borrar) + integrar en Rutinas (la lista usa standalone y se muestra una tarjeta del mesociclo activo).

**Architecture:** Componente presentacional `MesocycleView` (recibe meso + rutinas + `ahora` para tests deterministas; calcula la semana actual con `semanaActual`) montado por la página `/mesociclo/[id]` (live queries). La lista de rutinas pasa a `listStandaloneRoutines`; una `ActiveMesocycleCard` enlaza al mesociclo activo.

**Tech Stack:** Next.js 16 App Router (client, `useParams`/`useRouter`) · Dexie `useLiveQuery` · vitest (jsdom).

**Nota:** Fase **E3** (última de E) del spec `docs/superpowers/specs/2026-06-09-generador-mesociclos-ia-design.md`. E1 (entidad/repo/`semanaActual`/ruta) y E2 (generar/guardar, redirige a `/mesociclo/[id]`) ya mergeadas. "Empezar" un día = `startSession({ routineId })` → `/entrenar/[sessionId]` (sin gym picker, per spec).

**Disponible:** `getMesocycle`/`listMesocycles`/`deleteMesocycle`/`semanaActual` (E1), `listRoutinesByMesocycle`/`listStandaloneRoutines` (E1), `startSession` (`lib/repositories/workouts.ts`), tipos `Mesocycle`/`Routine`/`SemanaPlan`.

---

## File Structure

- **Create** `components/mesocycle-view.tsx` — vista presentacional del mesociclo.
- **Create** `components/mesocycle-view.test.tsx`.
- **Create** `app/mesociclo/[id]/page.tsx` — página (live queries + useParams).
- **Create** `components/active-mesocycle-card.tsx` — tarjeta del mesociclo activo.
- **Modify** `components/routine-list.tsx` — usa `listStandaloneRoutines`.
- **Modify** `components/routine-list.test.tsx` (si existe) — ajuste del mock/import.
- **Modify** `app/rutinas/page.tsx` — monta `<ActiveMesocycleCard />`.

---

## Task 1: `MesocycleView` + página `/mesociclo/[id]`

**Files:**
- Create: `components/mesocycle-view.tsx`
- Create: `components/mesocycle-view.test.tsx`
- Create: `app/mesociclo/[id]/page.tsx`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/mesocycles.ts` (`getMesocycle`, `deleteMesocycle`, `semanaActual`), `lib/repositories/routines.ts` (`listRoutinesByMesocycle`), `lib/repositories/workouts.ts` (`startSession({ routineId, gymId? })` → devuelve la sesión con `.id`), `components/start-workout.tsx` (idiom `const s = await startSession(...); router.push(\`/entrenar/${s.id}\`)`), `components/body-metric-card.tsx` (clases brutalist + botón borrar), `components/coach-chat.test.tsx` (mocks + `next/navigation`).

- [ ] **Step 1: Write the failing test** — create `components/mesocycle-view.test.tsx`:

```tsx
import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Mesocycle, Routine } from '@/lib/db/types';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));

const startSession = vi.fn().mockResolvedValue({ id: 'sess-1' });
vi.mock('@/lib/repositories/workouts', () => ({ startSession: (...a: unknown[]) => startSession(...a) }));

const deleteMesocycle = vi.fn().mockResolvedValue(undefined);
vi.mock('@/lib/repositories/mesocycles', async (orig) => {
  const real = await orig<typeof import('@/lib/repositories/mesocycles')>();
  return { ...real, deleteMesocycle: (...a: unknown[]) => deleteMesocycle(...a) };
});

import { MesocycleView } from '@/components/mesocycle-view';

const DIA = 86400000;
const meso: Mesocycle = {
  id: 'm1', userId: null, nombre: 'Hipertrofia', objetivo: 'hipertrofia', semanas: 6, diasPorSemana: 2,
  notas: 'foco pecho', fechaInicio: 0,
  progresion: [
    { semana: 1, descarga: false, ajuste: '3x10' },
    { semana: 2, descarga: false, ajuste: '4x10' },
  ],
  updatedAt: 1, deletedAt: null,
};
const routines: Routine[] = [
  { id: 'r1', userId: null, nombre: 'Push', orden: 0, archivada: false, mesocycleId: 'm1', updatedAt: 1, deletedAt: null },
  { id: 'r2', userId: null, nombre: 'Pull', orden: 1, archivada: false, mesocycleId: 'm1', updatedAt: 1, deletedAt: null },
];

beforeEach(() => { push.mockReset(); startSession.mockClear(); deleteMesocycle.mockClear(); });

it('resalta la semana actual (ahora = día 8 → semana 2)', () => {
  render(<MesocycleView meso={meso} routines={routines} ahora={8 * DIA} />);
  const sem2 = screen.getByText(/4x10/).closest('li')!;
  expect(sem2.className).toMatch(/bg-primary|font-bold|border/); // marca de actual
});

it('Empezar inicia sesión de la rutina y navega', async () => {
  render(<MesocycleView meso={meso} routines={routines} ahora={0} />);
  await userEvent.click(screen.getAllByRole('button', { name: /empezar/i })[0]);
  expect(startSession).toHaveBeenCalledWith({ routineId: 'r1' });
  await vi.waitFor(() => expect(push).toHaveBeenCalledWith('/entrenar/sess-1'));
});

it('borrar el mesociclo llama deleteMesocycle y navega a /rutinas', async () => {
  render(<MesocycleView meso={meso} routines={routines} ahora={0} />);
  await userEvent.click(screen.getByRole('button', { name: /borrar mesociclo/i }));
  expect(deleteMesocycle).toHaveBeenCalledWith('m1');
  await vi.waitFor(() => expect(push).toHaveBeenCalledWith('/rutinas'));
});
```

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement** — create `components/mesocycle-view.tsx`:

```tsx
'use client';

import { useRouter } from 'next/navigation';
import type { Mesocycle, Routine } from '@/lib/db/types';
import { semanaActual, deleteMesocycle } from '@/lib/repositories/mesocycles';
import { startSession } from '@/lib/repositories/workouts';
import { Button } from '@/components/ui/button';

export function MesocycleView({
  meso,
  routines,
  ahora = Date.now(),
}: {
  meso: Mesocycle;
  routines: Routine[];
  ahora?: number;
}) {
  const router = useRouter();
  const semana = semanaActual(meso, ahora);
  const dias = [...routines].sort((a, b) => a.orden - b.orden);

  async function empezar(routineId: string) {
    const s = await startSession({ routineId });
    router.push(`/entrenar/${s.id}`);
  }

  async function borrar() {
    await deleteMesocycle(meso.id);
    router.push('/rutinas');
  }

  return (
    <div className="space-y-4">
      <div className="brutal-box space-y-1 p-3">
        <h1 className="text-2xl font-bold">{meso.nombre}</h1>
        <p className="label-mono text-[10px] text-muted-foreground">
          {meso.objetivo} · {meso.semanas} semanas · {meso.diasPorSemana} días/semana
        </p>
        <p className="label-mono text-[10px] text-primary">Semana actual: {semana}/{meso.semanas}</p>
        {meso.notas && <p className="text-sm text-muted-foreground">{meso.notas}</p>}
      </div>

      <section className="brutal-box space-y-1 p-3">
        <h2 className="label-mono text-[10px] text-muted-foreground">Progresión</h2>
        <ul className="space-y-0.5">
          {meso.progresion.map((s) => (
            <li
              key={s.semana}
              className={`px-2 py-1 text-sm ${s.semana === semana ? 'border-2 border-foreground bg-primary/15 font-bold' : ''}`}
            >
              Sem {s.semana}{s.descarga ? ' (descarga)' : ''}: {s.ajuste}
            </li>
          ))}
        </ul>
      </section>

      <section className="space-y-2">
        <h2 className="label-mono text-[10px] text-muted-foreground">Días</h2>
        <ul className="brutal-box divide-y-2 divide-foreground">
          {dias.map((r) => (
            <li key={r.id} className="flex items-center justify-between gap-2 px-3 py-2.5">
              <span className="font-semibold">{r.nombre}</span>
              <Button size="sm" onClick={() => void empezar(r.id)}>
                Empezar
              </Button>
            </li>
          ))}
        </ul>
      </section>

      <button
        type="button"
        className="label-mono text-[11px] text-muted-foreground underline hover:text-destructive"
        onClick={() => void borrar()}
      >
        Borrar mesociclo
      </button>
    </div>
  );
}
```

- [ ] **Step 4: Create the page** — create `app/mesociclo/[id]/page.tsx`:

```tsx
'use client';

import { useParams } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { getMesocycle } from '@/lib/repositories/mesocycles';
import { listRoutinesByMesocycle } from '@/lib/repositories/routines';
import { MesocycleView } from '@/components/mesocycle-view';

export default function MesocyclePage() {
  const { id } = useParams<{ id: string }>();
  const meso = useLiveQuery(() => getMesocycle(id), [id]);
  const routines = useLiveQuery(() => listRoutinesByMesocycle(id), [id]);

  if (meso === undefined || routines === undefined) {
    return <p className="text-muted-foreground">Cargando…</p>;
  }
  if (meso === null || !meso) {
    return <p className="text-muted-foreground">Este mesociclo no existe.</p>;
  }
  return <MesocycleView meso={meso} routines={routines} />;
}
```
(`getMesocycle` devuelve `undefined` si no existe/está borrado; `useLiveQuery` devuelve `undefined` mientras carga — distingue ambos: trata "cargando" cuando `meso === undefined && routines === undefined` no es fiable. Simplifica: si `meso` es `undefined` por carga vs no-existe no se puede distinguir; usa este criterio: muestra "Cargando…" solo en el primer render con `routines === undefined`, y si tras cargar `meso` es `undefined`, muestra "no existe". Implementación práctica: trata `meso === undefined` como cargando y, una vez `routines` está definido pero `meso` sigue `undefined`, como inexistente.)

Resolución concreta (usa esto, evita ambigüedad):
```tsx
  if (routines === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!meso) return <p className="text-muted-foreground">Este mesociclo no existe.</p>;
  return <MesocycleView meso={meso} routines={routines} />;
```

- [ ] **Step 5: Run → PASS** (`npx vitest run components/mesocycle-view.test.tsx`) + `npx tsc --noEmit` + `npx eslint components/mesocycle-view.tsx app/mesociclo/[id]/page.tsx` + `npm run build` (confirma `/mesociclo/[id]`).

- [ ] **Step 6: Commit**

```bash
git add components/mesocycle-view.tsx components/mesocycle-view.test.tsx "app/mesociclo/[id]/page.tsx"
git commit -m "feat(meso): vista /mesociclo/[id] (semana actual, días, empezar, borrar)"
```

---

## Task 2: Lista standalone + tarjeta del mesociclo activo

**Files:**
- Modify: `components/routine-list.tsx`
- Modify: `components/routine-list.test.tsx` (si existe)
- Create: `components/active-mesocycle-card.tsx`
- Modify: `app/rutinas/page.tsx`

- [ ] **Step 0: READ FIRST**

Read `components/routine-list.tsx` (usa `listRoutines`), su test si existe, `lib/repositories/mesocycles.ts` (`listMesocycles`), `app/rutinas/page.tsx` (estructura actual con el header de Task E2 + `<RoutineList />`), `components/weekly-digest-mini.tsx` o similar para el idiom de tarjeta-enlace `brutal-box`.

- [ ] **Step 1: Switch RoutineList to standalone** — in `components/routine-list.tsx`:
- Change `import { listRoutines } from '@/lib/repositories/routines';` → `import { listStandaloneRoutines } from '@/lib/repositories/routines';`
- Change `useLiveQuery(() => listRoutines(), [])` → `useLiveQuery(() => listStandaloneRoutines(), [])`.
If `components/routine-list.test.tsx` exists and mocks/uses `listRoutines`, update it to `listStandaloneRoutines` (same behavior, just the standalone set). Run it to confirm green.

- [ ] **Step 2: Write the failing test for the card** — create `components/active-mesocycle-card.test.tsx`:

```tsx
import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { db } from '@/lib/db/database';
import { ActiveMesocycleCard } from '@/components/active-mesocycle-card';

beforeEach(async () => { await db.mesocycles.clear(); });

it('no muestra nada si no hay mesociclos', async () => {
  const { container } = render(<ActiveMesocycleCard />);
  // useLiveQuery resuelve async; en el primer render no hay tarjeta
  expect(container.querySelector('a')).toBeNull();
});

it('muestra el mesociclo más reciente como enlace', async () => {
  await db.mesocycles.put({ id: 'm9', userId: null, nombre: 'Mi plan', objetivo: 'fuerza', semanas: 5, diasPorSemana: 3, notas: null, progresion: [], fechaInicio: 1000, updatedAt: 1, deletedAt: null });
  render(<ActiveMesocycleCard />);
  const link = await screen.findByRole('link', { name: /mi plan/i });
  expect(link).toHaveAttribute('href', '/mesociclo/m9');
});
```

- [ ] **Step 3: Run → FAIL**.

- [ ] **Step 4: Implement** — create `components/active-mesocycle-card.tsx`:

```tsx
'use client';

import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { Sparkles } from 'lucide-react';
import { listMesocycles } from '@/lib/repositories/mesocycles';

export function ActiveMesocycleCard() {
  const mesos = useLiveQuery(() => listMesocycles(), []);
  const activo = mesos?.[0]; // listMesocycles ya ordena por fechaInicio desc
  if (!activo) return null;
  return (
    <Link
      href={`/mesociclo/${activo.id}`}
      className="brutal-box flex items-center justify-between gap-3 px-3 py-2.5 transition-transform active:translate-x-[2px] active:translate-y-[2px]"
    >
      <span className="flex items-center gap-2">
        <Sparkles className="size-4 text-primary" strokeWidth={2} aria-hidden="true" />
        <span className="font-semibold">{activo.nombre}</span>
      </span>
      <span className="label-mono text-[10px] text-muted-foreground">mesociclo →</span>
    </Link>
  );
}
```

- [ ] **Step 5: Mount on the routines page** — in `app/rutinas/page.tsx`:
1. Add `import { ActiveMesocycleCard } from '@/components/active-mesocycle-card';`
2. Render `<ActiveMesocycleCard />` between the header `<div>` and `<RoutineList />`.

- [ ] **Step 6: Run → PASS** + `npx tsc --noEmit` + `npx eslint components/routine-list.tsx components/active-mesocycle-card.tsx app/rutinas/page.tsx` + `npm run build`.

- [ ] **Step 7: Commit**

```bash
git add components/routine-list.tsx components/routine-list.test.tsx components/active-mesocycle-card.tsx components/active-mesocycle-card.test.tsx app/rutinas/page.tsx
git commit -m "feat(meso): rutinas usa standalone + tarjeta de mesociclo activo"
```

---

## Task 3: Verificación final

- [ ] **Step 1:** `npx vitest run && npx tsc --noEmit && npm run lint && npm run build`
Expected: todo verde; ruta `/mesociclo/[id]` presente.

---

## Self-Review (hecho)

- **Spec cobertura (E3):** `/mesociclo/[id]` con semana actual resaltada (`semanaActual`), progresión, días con "Empezar" (`startSession({routineId})` → `/entrenar/[id]`), borrar (tombstone + redirige) ✓; lista de rutinas usa `listStandaloneRoutines` (excluye las del mesociclo) ✓; tarjeta del mesociclo activo en Rutinas ✓.
- **Tipos consistentes:** `MesocycleView` presentacional con `ahora` inyectable (tests deterministas); `listMesocycles` ya ordena desc → `[0]` es el activo.
- **Casos límite:** mesociclo inexistente/borrado → mensaje; sin mesociclos → la tarjeta no renderiza; semana fuera de rango → `semanaActual` la acota.
- **Sin placeholders:** todo el código presente. El borrado no elimina en cascada las rutinas (documentado en el spec como fuera de alcance del MVP).
