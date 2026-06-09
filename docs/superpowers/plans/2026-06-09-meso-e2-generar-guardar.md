# Mesociclos E2 — Generar + revisar + guardar (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pantalla `/rutinas/generar` que pide los parámetros, llama a la ruta IA, muestra el mesociclo propuesto para revisión, y al confirmar lo guarda (crea el Mesocycle + las rutinas-día + los ejercicios, creando los nuevos). Más el acceso "Generar con IA" desde Rutinas.

**Architecture:** Una función de guardado `guardarMesociclo(propuesta)` (orquesta los repos mesocycles/routines/exercises, mapea ejercicios por nombre) + un componente cliente `MesoGenerator` (formulario → POST `/api/coach/mesociclo` → revisión → guardar) + la página y el acceso.

**Tech Stack:** Next.js 16 (client) · Dexie repos (E1) · `recogerSnapshot` · Tailwind "Brutalist Iron" · vitest (jsdom).

**Nota:** Fase **E2** del spec `docs/superpowers/specs/2026-06-09-generador-mesociclos-ia-design.md`. E1 (entidad `Mesocycle`, repo, `Routine.mesocycleId` + helpers, ruta `/api/coach/mesociclo`, `MESO_SCHEMA`/`promptMesociclo`) ya mergeada. La vista `/mesociclo/[id]` y el filtrado de la lista de rutinas son **E3**; aquí el guardado redirige a `/mesociclo/[id]` (página que añade E3).

**Disponible:** repos `createMesocycle` (E1), `createRoutine`/`addExerciseToRoutine`/`setRoutineMesocycle` (routines), `createExercise`/`listExercises` (exercises), `recogerSnapshot` (coach-snapshot), uniones `MUSCLE_GROUPS`/`EQUIPMENTS`/`EXERCISE_TYPES` (lib/db/types.ts), tipos `SemanaPlan`.

---

## File Structure

- **Modify** `lib/meso-prompt.ts` — exporta los tipos `PropuestaMesociclo`/`DiaPropuesto`/`EjercicioPropuesto`.
- **Create** `lib/save-mesocycle.ts` — `guardarMesociclo(propuesta)`.
- **Create** `lib/save-mesocycle.test.ts`.
- **Create** `components/meso-generator.tsx` — formulario + revisión + guardado.
- **Create** `components/meso-generator.test.tsx`.
- **Create** `app/rutinas/generar/page.tsx` — página que monta `MesoGenerator`.
- **Modify** `app/rutinas/page.tsx` — acceso "Generar con IA ✨".

---

## Task 1: Tipos de la propuesta + `guardarMesociclo`

**Files:**
- Modify: `lib/meso-prompt.ts`
- Create: `lib/save-mesocycle.ts`
- Create: `lib/save-mesocycle.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/meso-prompt.ts` (`MESO_SCHEMA`, `SemanaPlan` import), `lib/repositories/mesocycles.ts` (`createMesocycle`), `lib/repositories/routines.ts` (`createRoutine`, `addExerciseToRoutine`, `setRoutineMesocycle`), `lib/repositories/exercises.ts` (`createExercise` signature `NewExerciseInput`, `listExercises`), `lib/db/types.ts` (`MUSCLE_GROUPS`/`EQUIPMENTS`/`EXERCISE_TYPES`, `MuscleGroup`/`Equipment`/`ExerciseType`).

- [ ] **Step 1: Add the proposal types** — in `lib/meso-prompt.ts`, add (near the top, after imports):

```ts
import type { SemanaPlan } from '@/lib/db/types';

export interface EjercicioPropuesto {
  nombre: string;
  grupoMuscular: string;
  equipamiento: string;
  tipo: string;
  seriesObjetivo: number;
  repsObjetivo: number;
  descansoSegundos: number;
  nuevo: boolean;
}
export interface DiaPropuesto {
  nombre: string;
  orden: number;
  ejercicios: EjercicioPropuesto[];
}
export interface PropuestaMesociclo {
  nombre: string;
  objetivo: string;
  semanas: number;
  diasPorSemana: number;
  notas?: string;
  progresion: SemanaPlan[];
  dias: DiaPropuesto[];
}
```
(If `SemanaPlan` is already imported in the file, don't duplicate the import.)

- [ ] **Step 2: Write the failing test** — create `lib/save-mesocycle.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { guardarMesociclo } from '@/lib/save-mesocycle';
import { createExercise } from '@/lib/repositories/exercises';
import { listRoutinesByMesocycle, listRoutineExercises } from '@/lib/repositories/routines';
import { getMesocycle } from '@/lib/repositories/mesocycles';
import type { PropuestaMesociclo } from '@/lib/meso-prompt';

const propuesta: PropuestaMesociclo = {
  nombre: 'Hipertrofia 6 sem', objetivo: 'hipertrofia', semanas: 6, diasPorSemana: 2,
  notas: 'enfoque pecho', progresion: [{ semana: 1, descarga: false, ajuste: '3x10' }],
  dias: [
    { nombre: 'Push', orden: 0, ejercicios: [
      { nombre: 'Press Banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', seriesObjetivo: 4, repsObjetivo: 8, descansoSegundos: 120, nuevo: false },
    ] },
    { nombre: 'Pull', orden: 1, ejercicios: [
      { nombre: 'Remo Inventado', grupoMuscular: 'espalda', equipamiento: 'barra', tipo: 'compuesto', seriesObjetivo: 4, repsObjetivo: 10, descansoSegundos: 90, nuevo: true },
    ] },
  ],
};

beforeEach(async () => {
  await Promise.all([db.mesocycles.clear(), db.routines.clear(), db.routineExercises.clear(), db.exercises.clear()]);
});

describe('guardarMesociclo', () => {
  it('crea el mesociclo, las rutinas etiquetadas y mapea/crea ejercicios', async () => {
    // un ejercicio ya existente que debe reutilizarse por nombre normalizado
    await createExercise({ nombre: 'Press banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto' });
    const exAntes = (await db.exercises.toArray()).length;

    const id = await guardarMesociclo(propuesta);

    const meso = await getMesocycle(id);
    expect(meso?.nombre).toBe('Hipertrofia 6 sem');
    expect(meso?.notas).toBe('enfoque pecho');

    const rutinas = await listRoutinesByMesocycle(id);
    expect(rutinas.map((r) => r.nombre)).toEqual(['Push', 'Pull']);

    // 'Press Banca' reutiliza el existente; 'Remo Inventado' se crea → +1 ejercicio
    expect((await db.exercises.toArray()).length).toBe(exAntes + 1);

    const push = rutinas.find((r) => r.nombre === 'Push')!;
    const ejs = await listRoutineExercises(push.id);
    expect(ejs).toHaveLength(1);
    expect(ejs[0].seriesObjetivo).toBe(4);
    expect(ejs[0].repsObjetivo).toBe(8);
  });

  it('aplica fallback en uniones inválidas al crear un ejercicio', async () => {
    const id = await guardarMesociclo({
      ...propuesta, dias: [{ nombre: 'X', orden: 0, ejercicios: [
        { nombre: 'Cosa Rara', grupoMuscular: 'inventado', equipamiento: 'xxx', tipo: 'yyy', seriesObjetivo: 3, repsObjetivo: 12, descansoSegundos: 60, nuevo: true },
      ] }],
    });
    const rutinas = await listRoutinesByMesocycle(id);
    const ejs = await listRoutineExercises(rutinas[0].id);
    const ex = await db.exercises.get(ejs[0].exerciseId);
    expect(ex?.grupoMuscular).toBe('otro');
    expect(ex?.equipamiento).toBe('otro');
    expect(ex?.tipo).toBe('compuesto');
  });
});
```

- [ ] **Step 3: Run → FAIL**.

- [ ] **Step 4: Implement** — create `lib/save-mesocycle.ts`:

```ts
import {
  MUSCLE_GROUPS, EQUIPMENTS, EXERCISE_TYPES,
  type MuscleGroup, type Equipment, type ExerciseType,
} from '@/lib/db/types';
import { listExercises, createExercise } from '@/lib/repositories/exercises';
import { createRoutine, addExerciseToRoutine, setRoutineMesocycle } from '@/lib/repositories/routines';
import { createMesocycle } from '@/lib/repositories/mesocycles';
import type { PropuestaMesociclo, EjercicioPropuesto } from '@/lib/meso-prompt';

/** Normaliza un nombre para emparejar ejercicios: minúsculas, sin acentos, sin espacios extra. */
function norm(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim().replace(/\s+/g, ' ');
}

function comoGrupo(v: string): MuscleGroup {
  return (MUSCLE_GROUPS as string[]).includes(v) ? (v as MuscleGroup) : 'otro';
}
function comoEquip(v: string): Equipment {
  return (EQUIPMENTS as string[]).includes(v) ? (v as Equipment) : 'otro';
}
function comoTipo(v: string): ExerciseType {
  return (EXERCISE_TYPES as string[]).includes(v) ? (v as ExerciseType) : 'compuesto';
}

/**
 * Guarda una propuesta de mesociclo: crea el Mesocycle, y por cada día una rutina
 * etiquetada con su mesocycleId con sus ejercicios (reutilizando los del catálogo por
 * nombre normalizado, creando los que falten). Devuelve el id del mesociclo.
 */
export async function guardarMesociclo(propuesta: PropuestaMesociclo): Promise<string> {
  const meso = await createMesocycle({
    nombre: propuesta.nombre,
    objetivo: propuesta.objetivo,
    semanas: propuesta.semanas,
    diasPorSemana: propuesta.diasPorSemana,
    notas: propuesta.notas ?? null,
    progresion: propuesta.progresion,
    fechaInicio: Date.now(),
  });

  // Índice nombre normalizado → id de los ejercicios existentes.
  const existentes = await listExercises();
  const porNombre = new Map<string, string>();
  for (const e of existentes) porNombre.set(norm(e.nombre), e.id);

  async function resolverEjercicio(e: EjercicioPropuesto): Promise<string> {
    const clave = norm(e.nombre);
    const hit = porNombre.get(clave);
    if (hit) return hit;
    const nuevo = await createExercise({
      nombre: e.nombre.trim(),
      grupoMuscular: comoGrupo(e.grupoMuscular),
      equipamiento: comoEquip(e.equipamiento),
      tipo: comoTipo(e.tipo),
    });
    porNombre.set(clave, nuevo.id); // por si se repite en otro día
    return nuevo.id;
  }

  const dias = [...propuesta.dias].sort((a, b) => a.orden - b.orden);
  for (const dia of dias) {
    const rutina = await createRoutine({ nombre: dia.nombre });
    await setRoutineMesocycle(rutina.id, meso.id);
    for (const ej of dia.ejercicios) {
      const exerciseId = await resolverEjercicio(ej);
      await addExerciseToRoutine(rutina.id, {
        exerciseId,
        seriesObjetivo: ej.seriesObjetivo,
        repsObjetivo: ej.repsObjetivo,
        descansoSegundos: ej.descansoSegundos,
      });
    }
  }
  return meso.id;
}
```

- [ ] **Step 5: Run → PASS** + `npx tsc --noEmit` + `npx eslint lib/save-mesocycle.ts`.

- [ ] **Step 6: Commit**

```bash
git add lib/meso-prompt.ts lib/save-mesocycle.ts lib/save-mesocycle.test.ts
git commit -m "feat(meso): guardarMesociclo (crea mesociclo + rutinas + ejercicios)"
```

---

## Task 2: Componente `MesoGenerator` (formulario + revisión + guardar)

**Files:**
- Create: `components/meso-generator.tsx`
- Create: `components/meso-generator.test.tsx`

- [ ] **Step 0: READ FIRST**

Read `components/coach-chat.tsx` (cómo llama `recogerSnapshot(gymId)` y hace `fetch` en cliente; estados), `lib/repositories/exercises.ts` (`listExercises`), `lib/save-mesocycle.ts` (`guardarMesociclo`), `components/body-form.tsx` (clases brutalist de select/Input/Button), `components/coach-chat.test.tsx` (idiom de mocks incl. `next/navigation`). Para el router: `import { useRouter } from 'next/navigation'`.

- [ ] **Step 1: Write the failing test** — create `components/meso-generator.test.tsx`:

```tsx
import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));

const recogerSnapshot = vi.fn();
vi.mock('@/lib/coach-snapshot', () => ({ recogerSnapshot: (...a: unknown[]) => recogerSnapshot(...a) }));

const listExercises = vi.fn();
vi.mock('@/lib/repositories/exercises', () => ({ listExercises: (...a: unknown[]) => listExercises(...a) }));

const guardarMesociclo = vi.fn();
vi.mock('@/lib/save-mesocycle', () => ({ guardarMesociclo: (...a: unknown[]) => guardarMesociclo(...a) }));

import { MesoGenerator } from '@/components/meso-generator';

const PROPUESTA = {
  nombre: 'Plan IA', objetivo: 'hipertrofia', semanas: 6, diasPorSemana: 2,
  notas: 'x', progresion: [{ semana: 1, descarga: false, ajuste: '3x10' }],
  dias: [{ nombre: 'Push', orden: 0, ejercicios: [
    { nombre: 'Press Banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', seriesObjetivo: 4, repsObjetivo: 8, descansoSegundos: 120, nuevo: true },
  ] }],
};

beforeEach(() => {
  push.mockReset();
  recogerSnapshot.mockReset().mockResolvedValue({ estancados: [], semana: {}, grupos: [], cuerpo: { peso: null, medidas: [] } });
  listExercises.mockReset().mockResolvedValue([{ id: 'e1', nombre: 'Sentadilla', grupoMuscular: 'cuadriceps', equipamiento: 'barra', deletedAt: null }]);
  guardarMesociclo.mockReset().mockResolvedValue('meso-9');
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => PROPUESTA }));
});

it('generar: postea params+snapshot+catalogo y pinta la revisión', async () => {
  render(<MesoGenerator />);
  await userEvent.click(screen.getByRole('button', { name: /generar/i }));
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/coach/mesociclo', expect.objectContaining({ method: 'POST' })));
  const body = JSON.parse((fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls[0][1].body);
  expect(body.params.objetivo).toBeTruthy();
  expect(body.catalogo[0].nombre).toBe('Sentadilla');
  await waitFor(() => expect(screen.getByText('Plan IA')).toBeInTheDocument());
  expect(screen.getByText('Push')).toBeInTheDocument();
  expect(screen.getByText(/Press Banca/)).toBeInTheDocument();
});

it('guardar: llama guardarMesociclo y navega a /mesociclo/:id', async () => {
  render(<MesoGenerator />);
  await userEvent.click(screen.getByRole('button', { name: /generar/i }));
  await waitFor(() => expect(screen.getByText('Plan IA')).toBeInTheDocument());
  await userEvent.click(screen.getByRole('button', { name: /guardar mesociclo/i }));
  await waitFor(() => expect(guardarMesociclo).toHaveBeenCalledWith(PROPUESTA));
  expect(push).toHaveBeenCalledWith('/mesociclo/meso-9');
});

it('muestra error si la generación falla', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }));
  render(<MesoGenerator />);
  await userEvent.click(screen.getByRole('button', { name: /generar/i }));
  await waitFor(() => expect(screen.getByText(/no se pudo generar|no disponible/i)).toBeInTheDocument());
  expect(guardarMesociclo).not.toHaveBeenCalled();
});
```

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement** — create `components/meso-generator.tsx`:

```tsx
'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { recogerSnapshot } from '@/lib/coach-snapshot';
import { listExercises } from '@/lib/repositories/exercises';
import { guardarMesociclo } from '@/lib/save-mesocycle';
import type { PropuestaMesociclo } from '@/lib/meso-prompt';

const OBJETIVOS = ['hipertrofia', 'fuerza', 'general'];

export function MesoGenerator() {
  const router = useRouter();
  const [objetivo, setObjetivo] = useState('hipertrofia');
  const [diasPorSemana, setDias] = useState(4);
  const [semanas, setSemanas] = useState(6);
  const [minutosPorSesion, setMinutos] = useState(60);
  const [estado, setEstado] = useState<'form' | 'generando' | 'revision' | 'guardando'>('form');
  const [error, setError] = useState('');
  const [propuesta, setPropuesta] = useState<PropuestaMesociclo | null>(null);

  async function generar() {
    setError('');
    setEstado('generando');
    try {
      const snapshot = await recogerSnapshot();
      const catalogo = (await listExercises()).map((e) => ({
        nombre: e.nombre, grupo: e.grupoMuscular, equipamiento: e.equipamiento,
      }));
      const res = await fetch('/api/coach/mesociclo', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ params: { objetivo, diasPorSemana, semanas, minutosPorSesion }, snapshot, catalogo }),
      });
      if (!res.ok) throw new Error(String(res.status));
      const data = (await res.json()) as PropuestaMesociclo;
      setPropuesta(data);
      setEstado('revision');
    } catch {
      setError('No se pudo generar el mesociclo. Inténtalo de nuevo.');
      setEstado('form');
    }
  }

  async function guardar() {
    if (!propuesta) return;
    setEstado('guardando');
    try {
      const id = await guardarMesociclo(propuesta);
      router.push(`/mesociclo/${id}`);
    } catch {
      setError('No se pudo guardar.');
      setEstado('revision');
    }
  }

  if (estado === 'revision' || estado === 'guardando') {
    const p = propuesta!;
    return (
      <div className="space-y-4">
        <div className="brutal-box space-y-1 p-3">
          <h2 className="text-xl font-bold">{p.nombre}</h2>
          <p className="label-mono text-[10px] text-muted-foreground">
            {p.objetivo} · {p.semanas} semanas · {p.diasPorSemana} días/semana
          </p>
          {p.notas && <p className="text-sm text-muted-foreground">{p.notas}</p>}
        </div>

        <section className="brutal-box space-y-1 p-3">
          <h3 className="label-mono text-[10px] text-muted-foreground">Progresión</h3>
          <ul className="space-y-0.5 text-sm">
            {p.progresion.map((s) => (
              <li key={s.semana}>
                <span className="font-semibold">Sem {s.semana}{s.descarga ? ' (descarga)' : ''}:</span> {s.ajuste}
              </li>
            ))}
          </ul>
        </section>

        {[...p.dias].sort((a, b) => a.orden - b.orden).map((dia) => (
          <section key={dia.orden} className="brutal-box space-y-2 p-3">
            <h3 className="font-bold">{dia.nombre}</h3>
            <ul className="divide-y-2 divide-foreground">
              {dia.ejercicios.map((e, i) => (
                <li key={i} className="flex items-center justify-between gap-2 py-1.5 text-sm">
                  <span>
                    {e.nombre}
                    {e.nuevo && <span className="label-mono ml-2 text-[9px] text-primary">NUEVO</span>}
                  </span>
                  <span className="label-mono text-[10px] text-muted-foreground tabular-nums">
                    {e.seriesObjetivo}×{e.repsObjetivo} · {e.descansoSegundos}s
                  </span>
                </li>
              ))}
            </ul>
          </section>
        ))}

        {error && <p className="text-sm text-destructive">{error}</p>}
        <div className="flex gap-3">
          <Button onClick={guardar} disabled={estado === 'guardando'} className="flex-1">
            {estado === 'guardando' ? 'Guardando…' : 'Guardar mesociclo'}
          </Button>
          <Button variant="outline" onClick={() => setEstado('form')} disabled={estado === 'guardando'}>
            Descartar
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="brutal-box space-y-3 p-3">
      <div className="space-y-1">
        <Label htmlFor="objetivo">Objetivo</Label>
        <select id="objetivo" className="w-full border-2 border-foreground bg-card p-2" value={objetivo} onChange={(e) => setObjetivo(e.target.value)}>
          {OBJETIVOS.map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
      </div>
      <div className="grid grid-cols-3 gap-3">
        <div className="space-y-1">
          <Label htmlFor="dias">Días/semana</Label>
          <select id="dias" className="w-full border-2 border-foreground bg-card p-2" value={diasPorSemana} onChange={(e) => setDias(Number(e.target.value))}>
            {[2, 3, 4, 5, 6].map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </div>
        <div className="space-y-1">
          <Label htmlFor="semanas">Semanas</Label>
          <select id="semanas" className="w-full border-2 border-foreground bg-card p-2" value={semanas} onChange={(e) => setSemanas(Number(e.target.value))}>
            {[4, 5, 6, 7, 8].map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </div>
        <div className="space-y-1">
          <Label htmlFor="minutos">Min/sesión</Label>
          <select id="minutos" className="w-full border-2 border-foreground bg-card p-2" value={minutosPorSesion} onChange={(e) => setMinutos(Number(e.target.value))}>
            {[30, 45, 60, 75, 90].map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </div>
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
      <Button onClick={generar} disabled={estado === 'generando'} className="w-full">
        {estado === 'generando' ? 'Generando…' : 'Generar mesociclo'}
      </Button>
    </div>
  );
}
```

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit` + `npx eslint components/meso-generator.tsx`.

- [ ] **Step 5: Commit**

```bash
git add components/meso-generator.tsx components/meso-generator.test.tsx
git commit -m "feat(meso): MesoGenerator (formulario + revisión + guardar)"
```

---

## Task 3: Página `/rutinas/generar` + acceso desde Rutinas

**Files:**
- Create: `app/rutinas/generar/page.tsx`
- Modify: `app/rutinas/page.tsx`

- [ ] **Step 0: READ FIRST**

Read `app/rutinas/page.tsx` (header con "Nueva" + `<RoutineList />`) y `app/coach/page.tsx` (estructura de página cliente con header). `MesoGenerator` es cliente.

- [ ] **Step 1: Create the page** — create `app/rutinas/generar/page.tsx`:

```tsx
import { MesoGenerator } from '@/components/meso-generator';

export default function GenerarRutinaPage() {
  return (
    <div className="space-y-4">
      <div>
        <p className="label-mono text-[11px] text-muted-foreground">Coach IA</p>
        <h1 className="text-2xl font-bold">Generar mesociclo</h1>
      </div>
      <MesoGenerator />
    </div>
  );
}
```

- [ ] **Step 2: Add the entry point** — in `app/rutinas/page.tsx`, add a "Generar con IA" link next to "Nueva" (mirror the existing `<Link>` styling, add a `Sparkles` icon from lucide):

```tsx
import Link from 'next/link';
import { Sparkles } from 'lucide-react';
import { RoutineList } from '@/components/routine-list';

export default function RutinasPage() {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Rutinas</h1>
        <div className="flex items-center gap-2">
          <Link href="/rutinas/generar" className="flex items-center gap-1 rounded-md border-2 border-foreground px-3 py-1.5 text-sm">
            <Sparkles className="size-4 text-primary" strokeWidth={2} aria-hidden="true" />
            Generar con IA
          </Link>
          <Link href="/rutinas/nueva" className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground">
            Nueva
          </Link>
        </div>
      </div>
      <RoutineList />
    </div>
  );
}
```

- [ ] **Step 3: Typecheck + lint + build**

Run: `npx tsc --noEmit && npx eslint app/rutinas/generar/page.tsx app/rutinas/page.tsx && npm run build`
Expected: clean; `/rutinas/generar` aparece en el build.

- [ ] **Step 4: Commit**

```bash
git add app/rutinas/generar/page.tsx app/rutinas/page.tsx
git commit -m "feat(meso): página /rutinas/generar + acceso desde Rutinas"
```

---

## Task 4: Verificación final

- [ ] **Step 1:** `npx vitest run && npx tsc --noEmit && npm run lint && npm run build`
Expected: todo verde; rutas `/rutinas/generar` y `/api/coach/mesociclo` presentes.

---

## Self-Review (hecho)

- **Spec cobertura (E2):** formulario corto (objetivo/días/semanas/minutos) ✓; POST a la ruta con params+snapshot+catálogo (`listExercises` mapeado) ✓; revisión que pinta cabecera + progresión + días con targets y marca NUEVO ✓; guardado (`guardarMesociclo`: crea Mesocycle + rutinas etiquetadas + ejercicios mapeados/creados con fallback de uniones) ✓; redirige a `/mesociclo/[id]` ✓; acceso "Generar con IA" en Rutinas ✓; error de generación no guarda nada ✓.
- **Tipos consistentes:** `PropuestaMesociclo`/`DiaPropuesto`/`EjercicioPropuesto` casan con `MESO_SCHEMA` (E1) y con `guardarMesociclo`; mapeo de uniones con `MUSCLE_GROUPS`/`EQUIPMENTS`/`EXERCISE_TYPES` reales.
- **Casos límite:** ejercicio existente reutilizado por nombre normalizado (acentos/espacios); nuevo creado; uniones inválidas → fallback otro/otro/compuesto; descartar revisión vuelve al formulario.
- **Sin placeholders:** todo el código presente. La vista `/mesociclo/[id]` (destino del redirect) es E3.
