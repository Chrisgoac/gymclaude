# GymLog — Fase 1: Cimientos + Catálogo de ejercicios — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Montar una PWA Next.js instalable y offline con una capa de datos local (Dexie/IndexedDB) y un catálogo de ejercicios precargado que el usuario puede explorar, crear, editar y borrar.

**Architecture:** App Next.js (App Router, TS) 100% cliente para esta fase. Los datos viven en IndexedDB vía Dexie; la UI es reactiva con `useLiveQuery`. Cada registro lleva metadatos de sincronización (`id`, `updatedAt`, `deletedAt`) preparados para la Fase 4, pero aquí no hay servidor ni auth. Borrado lógico (tombstone) en lugar de borrado físico.

**Tech Stack:** Next.js (App Router) · TypeScript · Tailwind · shadcn/ui · Dexie + dexie-react-hooks · Serwist (PWA) · Vitest + React Testing Library + fake-indexeddb.

**Spec de referencia:** `docs/superpowers/specs/2026-05-23-gymlog-design.md`

---

## File Structure (lo que crea esta fase)

- `app/layout.tsx` — layout raíz + navegación inferior
- `app/page.tsx` — pantalla "Entrenar" (stub en esta fase)
- `app/rutinas/page.tsx`, `app/progreso/page.tsx`, `app/historial/page.tsx` — stubs de pestañas
- `app/ejercicios/page.tsx` — pantalla del catálogo de ejercicios (el entregable principal)
- `app/manifest.ts` — manifest de la PWA
- `app/sw.ts` — service worker (Serwist)
- `components/bottom-nav.tsx` — barra de navegación inferior
- `components/exercise-list.tsx` — lista/buscador de ejercicios agrupada por músculo
- `components/exercise-form.tsx` — formulario crear/editar ejercicio
- `lib/db/types.ts` — tipos del dominio (`SyncMeta`, `Exercise`, enums)
- `lib/db/database.ts` — definición de la base Dexie
- `lib/db/seed.ts` — datos del catálogo precargado + `seedCatalogIfEmpty()`
- `lib/repositories/exercises.ts` — CRUD de ejercicios sobre Dexie
- `lib/labels.ts` — etiquetas en español para enums (grupo muscular, equipamiento, tipo)
- Tests en `*.test.ts(x)` junto al código o en `tests/`

---

## Task 1: Scaffold del proyecto Next.js

**Files:**
- Create: estructura base de Next.js en la raíz del repo (preservando `.git` y `docs/`)

- [ ] **Step 1: Generar el proyecto en un directorio temporal y copiarlo a la raíz**

El repo ya contiene `.git` y `docs/`, así que generamos en `/tmp` y copiamos sin pisar git:

```bash
npx create-next-app@latest /tmp/gymlog-scaffold \
  --typescript --tailwind --app --eslint \
  --no-src-dir --import-alias "@/*" --use-npm --yes
rsync -a --exclude='.git' /tmp/gymlog-scaffold/ ./
rm -rf /tmp/gymlog-scaffold
```

- [ ] **Step 2: Verificar que el proyecto arranca**

Run: `npm run build`
Expected: build termina sin errores (genera `.next/`).

- [ ] **Step 3: Añadir `.next/`, `node_modules/` y similares a `.gitignore` (create-next-app ya lo hace) y commitear el scaffold**

```bash
git add -A
git commit -m "chore: scaffold Next.js app (App Router, TS, Tailwind)"
```

---

## Task 2: Configurar el arnés de tests (Vitest + RTL + fake-indexeddb)

**Files:**
- Create: `vitest.config.ts`
- Create: `vitest.setup.ts`
- Modify: `package.json` (script `test`)
- Test: `lib/smoke.test.ts`

- [ ] **Step 1: Instalar dependencias de test**

```bash
npm install -D vitest @vitejs/plugin-react jsdom \
  @testing-library/react @testing-library/jest-dom @testing-library/user-event \
  fake-indexeddb
```

- [ ] **Step 2: Crear `vitest.config.ts`**

```ts
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./', import.meta.url)),
    },
  },
});
```

- [ ] **Step 3: Crear `vitest.setup.ts`**

`fake-indexeddb/auto` instala un IndexedDB en memoria para que Dexie funcione en los tests.

```ts
import '@testing-library/jest-dom/vitest';
import 'fake-indexeddb/auto';
```

- [ ] **Step 4: Añadir el script de test en `package.json`**

En la sección `"scripts"` añade:

```json
"test": "vitest run",
"test:watch": "vitest"
```

- [ ] **Step 5: Escribir un test de humo**

Crea `lib/smoke.test.ts`:

```ts
import { describe, it, expect } from 'vitest';

describe('arnés de tests', () => {
  it('suma correctamente', () => {
    expect(1 + 1).toBe(2);
  });

  it('tiene IndexedDB disponible (fake-indexeddb)', () => {
    expect(typeof indexedDB).not.toBe('undefined');
  });
});
```

- [ ] **Step 6: Ejecutar los tests**

Run: `npm test`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "test: set up Vitest + RTL + fake-indexeddb harness"
```

---

## Task 3: Tipos del dominio

**Files:**
- Create: `lib/db/types.ts`
- Create: `lib/labels.ts`

- [ ] **Step 1: Crear `lib/db/types.ts`**

```ts
/** Metadatos comunes para sincronización (Fase 4). En Fase 1 ya se rellenan. */
export interface SyncMeta {
  id: string; // UUID generado en cliente (crypto.randomUUID)
  updatedAt: number; // epoch ms
  deletedAt: number | null; // tombstone: null = activo
}

export type MuscleGroup =
  | 'pecho'
  | 'espalda'
  | 'hombros'
  | 'biceps'
  | 'triceps'
  | 'cuadriceps'
  | 'femoral'
  | 'gluteo'
  | 'gemelo'
  | 'abdomen'
  | 'antebrazo'
  | 'otro';

export type Equipment =
  | 'barra'
  | 'mancuerna'
  | 'maquina'
  | 'polea'
  | 'peso_corporal'
  | 'otro';

export type ExerciseType = 'compuesto' | 'aislamiento';

export interface Exercise extends SyncMeta {
  /** null = catálogo global precargado. En Fase 4 se asigna el id de usuario. */
  userId: string | null;
  nombre: string;
  grupoMuscular: MuscleGroup;
  equipamiento: Equipment;
  tipo: ExerciseType;
  videoUrl?: string;
  notas?: string;
  esPersonalizado: boolean;
}

export const MUSCLE_GROUPS: MuscleGroup[] = [
  'pecho', 'espalda', 'hombros', 'biceps', 'triceps',
  'cuadriceps', 'femoral', 'gluteo', 'gemelo', 'abdomen', 'antebrazo', 'otro',
];

export const EQUIPMENTS: Equipment[] = [
  'barra', 'mancuerna', 'maquina', 'polea', 'peso_corporal', 'otro',
];

export const EXERCISE_TYPES: ExerciseType[] = ['compuesto', 'aislamiento'];
```

- [ ] **Step 2: Crear `lib/labels.ts` (etiquetas en español para la UI)**

```ts
import type { MuscleGroup, Equipment, ExerciseType } from '@/lib/db/types';

export const muscleGroupLabel: Record<MuscleGroup, string> = {
  pecho: 'Pecho',
  espalda: 'Espalda',
  hombros: 'Hombros',
  biceps: 'Bíceps',
  triceps: 'Tríceps',
  cuadriceps: 'Cuádriceps',
  femoral: 'Femoral',
  gluteo: 'Glúteo',
  gemelo: 'Gemelo',
  abdomen: 'Abdomen',
  antebrazo: 'Antebrazo',
  otro: 'Otro',
};

export const equipmentLabel: Record<Equipment, string> = {
  barra: 'Barra',
  mancuerna: 'Mancuerna',
  maquina: 'Máquina',
  polea: 'Polea',
  peso_corporal: 'Peso corporal',
  otro: 'Otro',
};

export const exerciseTypeLabel: Record<ExerciseType, string> = {
  compuesto: 'Compuesto',
  aislamiento: 'Aislamiento',
};
```

- [ ] **Step 3: Verificar que compila (typecheck)**

Run: `npx tsc --noEmit`
Expected: sin errores.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add domain types and Spanish labels for exercises"
```

---

## Task 4: Base de datos Dexie

**Files:**
- Create: `lib/db/database.ts`
- Test: `lib/db/database.test.ts`

- [ ] **Step 1: Instalar Dexie**

```bash
npm install dexie dexie-react-hooks
```

- [ ] **Step 2: Escribir el test que falla**

Crea `lib/db/database.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';

describe('GymLogDB', () => {
  beforeEach(async () => {
    await db.exercises.clear();
  });

  it('tiene la tabla exercises', () => {
    expect(db.exercises).toBeDefined();
  });

  it('persiste y recupera un ejercicio por id', async () => {
    await db.exercises.put({
      id: 'x1',
      userId: null,
      nombre: 'Press banca',
      grupoMuscular: 'pecho',
      equipamiento: 'barra',
      tipo: 'compuesto',
      esPersonalizado: false,
      updatedAt: 1,
      deletedAt: null,
    });
    const found = await db.exercises.get('x1');
    expect(found?.nombre).toBe('Press banca');
  });
});
```

- [ ] **Step 3: Ejecutar el test para verificar que falla**

Run: `npx vitest run lib/db/database.test.ts`
Expected: FAIL (no existe `@/lib/db/database`).

- [ ] **Step 4: Implementar `lib/db/database.ts`**

```ts
import Dexie, { type Table } from 'dexie';
import type { Exercise } from './types';

export class GymLogDB extends Dexie {
  exercises!: Table<Exercise, string>;

  constructor() {
    super('gymlog');
    this.version(1).stores({
      // PK 'id' + índices para consultas y filtrado por tombstone
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
    });
  }
}

export const db = new GymLogDB();
```

- [ ] **Step 5: Ejecutar el test para verificar que pasa**

Run: `npx vitest run lib/db/database.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add Dexie database with exercises table"
```

---

## Task 5: Repositorio de ejercicios (CRUD)

**Files:**
- Create: `lib/repositories/exercises.ts`
- Test: `lib/repositories/exercises.test.ts`

- [ ] **Step 1: Escribir los tests que fallan**

Crea `lib/repositories/exercises.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import {
  createExercise,
  listExercises,
  getExercise,
  updateExercise,
  softDeleteExercise,
} from '@/lib/repositories/exercises';

describe('repositorio de ejercicios', () => {
  beforeEach(async () => {
    await db.exercises.clear();
  });

  it('crea un ejercicio con id, esPersonalizado=true y sin tombstone', async () => {
    const ex = await createExercise({
      nombre: 'Curl martillo',
      grupoMuscular: 'biceps',
      equipamiento: 'mancuerna',
      tipo: 'aislamiento',
    });
    expect(ex.id).toBeTruthy();
    expect(ex.esPersonalizado).toBe(true);
    expect(ex.deletedAt).toBeNull();
    expect(await getExercise(ex.id)).toMatchObject({ nombre: 'Curl martillo' });
  });

  it('lista solo los ejercicios no borrados, ordenados por nombre', async () => {
    await createExercise({ nombre: 'Zancada', grupoMuscular: 'cuadriceps', equipamiento: 'mancuerna', tipo: 'compuesto' });
    await createExercise({ nombre: 'Aperturas', grupoMuscular: 'pecho', equipamiento: 'polea', tipo: 'aislamiento' });
    const list = await listExercises();
    expect(list.map((e) => e.nombre)).toEqual(['Aperturas', 'Zancada']);
  });

  it('actualiza un ejercicio y refresca updatedAt', async () => {
    const ex = await createExercise({ nombre: 'Sentadilla', grupoMuscular: 'cuadriceps', equipamiento: 'barra', tipo: 'compuesto' });
    const before = ex.updatedAt;
    await new Promise((r) => setTimeout(r, 2));
    await updateExercise(ex.id, { notas: 'Profundidad completa' });
    const after = await getExercise(ex.id);
    expect(after?.notas).toBe('Profundidad completa');
    expect(after!.updatedAt).toBeGreaterThan(before);
  });

  it('borra de forma lógica (tombstone) y deja de listarse', async () => {
    const ex = await createExercise({ nombre: 'Peso muerto', grupoMuscular: 'espalda', equipamiento: 'barra', tipo: 'compuesto' });
    await softDeleteExercise(ex.id);
    const stored = await getExercise(ex.id);
    expect(stored?.deletedAt).not.toBeNull();
    expect(await listExercises()).toHaveLength(0);
  });
});
```

- [ ] **Step 2: Ejecutar para verificar que fallan**

Run: `npx vitest run lib/repositories/exercises.test.ts`
Expected: FAIL (no existe el módulo).

- [ ] **Step 3: Implementar `lib/repositories/exercises.ts`**

```ts
import { db } from '@/lib/db/database';
import type { Exercise } from '@/lib/db/types';

/** Campos que aporta el usuario al crear; el resto se genera. */
export type NewExerciseInput = Pick<
  Exercise,
  'nombre' | 'grupoMuscular' | 'equipamiento' | 'tipo'
> &
  Partial<Pick<Exercise, 'videoUrl' | 'notas'>>;

export type ExerciseChanges = Partial<
  Pick<Exercise, 'nombre' | 'grupoMuscular' | 'equipamiento' | 'tipo' | 'videoUrl' | 'notas'>
>;

export async function createExercise(input: NewExerciseInput): Promise<Exercise> {
  const exercise: Exercise = {
    ...input,
    id: crypto.randomUUID(),
    userId: null, // Fase 4 asignará el id de usuario al iniciar sesión
    esPersonalizado: true,
    updatedAt: Date.now(),
    deletedAt: null,
  };
  await db.exercises.put(exercise);
  return exercise;
}

export async function getExercise(id: string): Promise<Exercise | undefined> {
  return db.exercises.get(id);
}

export async function listExercises(): Promise<Exercise[]> {
  const all = await db.exercises.orderBy('nombre').toArray();
  return all.filter((e) => e.deletedAt === null);
}

export async function updateExercise(id: string, changes: ExerciseChanges): Promise<void> {
  await db.exercises.update(id, { ...changes, updatedAt: Date.now() });
}

export async function softDeleteExercise(id: string): Promise<void> {
  const now = Date.now();
  await db.exercises.update(id, { deletedAt: now, updatedAt: now });
}
```

- [ ] **Step 4: Ejecutar para verificar que pasan**

Run: `npx vitest run lib/repositories/exercises.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add exercises repository (CRUD with soft delete)"
```

---

## Task 6: Datos semilla del catálogo + sembrado idempotente

**Files:**
- Create: `lib/db/seed.ts`
- Test: `lib/db/seed.test.ts`

- [ ] **Step 1: Escribir los tests que fallan**

Crea `lib/db/seed.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { CATALOG_SEED, seedCatalogIfEmpty } from '@/lib/db/seed';

describe('sembrado del catálogo', () => {
  beforeEach(async () => {
    await db.exercises.clear();
  });

  it('siembra el catálogo cuando la tabla está vacía', async () => {
    await seedCatalogIfEmpty();
    expect(await db.exercises.count()).toBe(CATALOG_SEED.length);
  });

  it('no duplica si ya hay datos', async () => {
    await seedCatalogIfEmpty();
    await seedCatalogIfEmpty();
    expect(await db.exercises.count()).toBe(CATALOG_SEED.length);
  });

  it('todos los seeds son globales (userId null) y no personalizados', async () => {
    await seedCatalogIfEmpty();
    const all = await db.exercises.toArray();
    expect(all.every((e) => e.userId === null && e.esPersonalizado === false)).toBe(true);
  });
});
```

- [ ] **Step 2: Ejecutar para verificar que fallan**

Run: `npx vitest run lib/db/seed.test.ts`
Expected: FAIL (no existe el módulo).

- [ ] **Step 3: Implementar `lib/db/seed.ts`**

IDs estables con prefijo `seed-` para que el upsert sea idempotente. Lista curada de ejercicios comunes.

```ts
import { db } from '@/lib/db/database';
import type { Exercise, MuscleGroup, Equipment, ExerciseType } from './types';

type SeedDef = {
  slug: string;
  nombre: string;
  grupoMuscular: MuscleGroup;
  equipamiento: Equipment;
  tipo: ExerciseType;
};

const DEFS: SeedDef[] = [
  // Pecho
  { slug: 'press-banca', nombre: 'Press de banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto' },
  { slug: 'press-inclinado-mancuerna', nombre: 'Press inclinado con mancuernas', grupoMuscular: 'pecho', equipamiento: 'mancuerna', tipo: 'compuesto' },
  { slug: 'aperturas-polea', nombre: 'Aperturas en polea', grupoMuscular: 'pecho', equipamiento: 'polea', tipo: 'aislamiento' },
  { slug: 'fondos', nombre: 'Fondos en paralelas', grupoMuscular: 'pecho', equipamiento: 'peso_corporal', tipo: 'compuesto' },
  // Espalda
  { slug: 'dominadas', nombre: 'Dominadas', grupoMuscular: 'espalda', equipamiento: 'peso_corporal', tipo: 'compuesto' },
  { slug: 'jalon-al-pecho', nombre: 'Jalón al pecho', grupoMuscular: 'espalda', equipamiento: 'polea', tipo: 'compuesto' },
  { slug: 'remo-barra', nombre: 'Remo con barra', grupoMuscular: 'espalda', equipamiento: 'barra', tipo: 'compuesto' },
  { slug: 'remo-mancuerna', nombre: 'Remo con mancuerna', grupoMuscular: 'espalda', equipamiento: 'mancuerna', tipo: 'compuesto' },
  { slug: 'peso-muerto', nombre: 'Peso muerto', grupoMuscular: 'espalda', equipamiento: 'barra', tipo: 'compuesto' },
  // Hombros
  { slug: 'press-militar', nombre: 'Press militar', grupoMuscular: 'hombros', equipamiento: 'barra', tipo: 'compuesto' },
  { slug: 'elevaciones-laterales', nombre: 'Elevaciones laterales', grupoMuscular: 'hombros', equipamiento: 'mancuerna', tipo: 'aislamiento' },
  { slug: 'pajaros', nombre: 'Pájaros (deltoide posterior)', grupoMuscular: 'hombros', equipamiento: 'mancuerna', tipo: 'aislamiento' },
  // Bíceps
  { slug: 'curl-barra', nombre: 'Curl con barra', grupoMuscular: 'biceps', equipamiento: 'barra', tipo: 'aislamiento' },
  { slug: 'curl-martillo', nombre: 'Curl martillo', grupoMuscular: 'biceps', equipamiento: 'mancuerna', tipo: 'aislamiento' },
  // Tríceps
  { slug: 'extension-polea', nombre: 'Extensión de tríceps en polea', grupoMuscular: 'triceps', equipamiento: 'polea', tipo: 'aislamiento' },
  { slug: 'press-frances', nombre: 'Press francés', grupoMuscular: 'triceps', equipamiento: 'barra', tipo: 'aislamiento' },
  // Cuádriceps
  { slug: 'sentadilla', nombre: 'Sentadilla', grupoMuscular: 'cuadriceps', equipamiento: 'barra', tipo: 'compuesto' },
  { slug: 'prensa', nombre: 'Prensa de piernas', grupoMuscular: 'cuadriceps', equipamiento: 'maquina', tipo: 'compuesto' },
  { slug: 'extension-cuadriceps', nombre: 'Extensión de cuádriceps', grupoMuscular: 'cuadriceps', equipamiento: 'maquina', tipo: 'aislamiento' },
  { slug: 'zancada', nombre: 'Zancadas', grupoMuscular: 'cuadriceps', equipamiento: 'mancuerna', tipo: 'compuesto' },
  // Femoral / Glúteo
  { slug: 'curl-femoral', nombre: 'Curl femoral tumbado', grupoMuscular: 'femoral', equipamiento: 'maquina', tipo: 'aislamiento' },
  { slug: 'peso-muerto-rumano', nombre: 'Peso muerto rumano', grupoMuscular: 'femoral', equipamiento: 'barra', tipo: 'compuesto' },
  { slug: 'hip-thrust', nombre: 'Hip thrust', grupoMuscular: 'gluteo', equipamiento: 'barra', tipo: 'compuesto' },
  // Gemelo / Abdomen
  { slug: 'elevacion-talones', nombre: 'Elevación de talones de pie', grupoMuscular: 'gemelo', equipamiento: 'maquina', tipo: 'aislamiento' },
  { slug: 'crunch', nombre: 'Crunch abdominal', grupoMuscular: 'abdomen', equipamiento: 'peso_corporal', tipo: 'aislamiento' },
  { slug: 'plancha', nombre: 'Plancha', grupoMuscular: 'abdomen', equipamiento: 'peso_corporal', tipo: 'aislamiento' },
];

export const CATALOG_SEED: Exercise[] = DEFS.map((d) => ({
  id: `seed-${d.slug}`,
  userId: null,
  nombre: d.nombre,
  grupoMuscular: d.grupoMuscular,
  equipamiento: d.equipamiento,
  tipo: d.tipo,
  esPersonalizado: false,
  updatedAt: 0,
  deletedAt: null,
}));

export async function seedCatalogIfEmpty(): Promise<void> {
  const count = await db.exercises.count();
  if (count === 0) {
    await db.exercises.bulkPut(CATALOG_SEED);
  }
}
```

- [ ] **Step 4: Ejecutar para verificar que pasan**

Run: `npx vitest run lib/db/seed.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add seed catalog of common exercises (idempotent)"
```

---

## Task 7: Componentes shadcn/ui base

**Files:**
- Create: `components.json`, `components/ui/*` (generados por el CLI)
- Modify: configuración de Tailwind/CSS (generada por el CLI)

- [ ] **Step 1: Inicializar shadcn/ui con valores por defecto**

```bash
npx shadcn@latest init -d
```

(Si pide confirmación, acepta los valores por defecto: estilo "new-york", color base "neutral", CSS variables = sí.)

- [ ] **Step 2: Añadir los componentes que usaremos**

```bash
npx shadcn@latest add button input label card select textarea -y
```

- [ ] **Step 3: Verificar que el build sigue pasando**

Run: `npm run build`
Expected: build sin errores.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: init shadcn/ui and add base components"
```

---

## Task 8: Shell de la app + navegación inferior

**Files:**
- Create: `components/bottom-nav.tsx`
- Modify: `app/layout.tsx`
- Modify: `app/page.tsx` (stub "Entrenar")
- Create: `app/rutinas/page.tsx`, `app/progreso/page.tsx`, `app/historial/page.tsx` (stubs)
- Test: `components/bottom-nav.test.tsx`

- [ ] **Step 1: Escribir el test que falla**

Crea `components/bottom-nav.test.tsx`:

```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BottomNav } from '@/components/bottom-nav';

vi.mock('next/navigation', () => ({
  usePathname: () => '/',
}));

describe('BottomNav', () => {
  it('muestra las cuatro pestañas', () => {
    render(<BottomNav />);
    expect(screen.getByText('Entrenar')).toBeInTheDocument();
    expect(screen.getByText('Rutinas')).toBeInTheDocument();
    expect(screen.getByText('Progreso')).toBeInTheDocument();
    expect(screen.getByText('Historial')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Ejecutar para verificar que falla**

Run: `npx vitest run components/bottom-nav.test.tsx`
Expected: FAIL (no existe el componente).

- [ ] **Step 3: Implementar `components/bottom-nav.tsx`**

```tsx
'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';

const TABS = [
  { href: '/', label: 'Entrenar' },
  { href: '/rutinas', label: 'Rutinas' },
  { href: '/progreso', label: 'Progreso' },
  { href: '/historial', label: 'Historial' },
] as const;

export function BottomNav() {
  const pathname = usePathname();
  return (
    <nav className="fixed inset-x-0 bottom-0 z-10 grid grid-cols-4 border-t bg-background">
      {TABS.map((tab) => {
        const active = tab.href === '/' ? pathname === '/' : pathname.startsWith(tab.href);
        return (
          <Link
            key={tab.href}
            href={tab.href}
            className={`py-3 text-center text-sm ${active ? 'font-semibold text-primary' : 'text-muted-foreground'}`}
          >
            {tab.label}
          </Link>
        );
      })}
    </nav>
  );
}
```

- [ ] **Step 4: Ejecutar para verificar que pasa**

Run: `npx vitest run components/bottom-nav.test.tsx`
Expected: PASS.

- [ ] **Step 5: Integrar la nav en `app/layout.tsx`**

Reemplaza el contenido de `app/layout.tsx` por:

```tsx
import type { Metadata } from 'next';
import './globals.css';
import { BottomNav } from '@/components/bottom-nav';

export const metadata: Metadata = {
  title: 'GymLog',
  description: 'App personal de gimnasio: rutinas, pesos y entrenos',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="es">
      <body className="min-h-screen pb-16">
        <main className="mx-auto max-w-md p-4">{children}</main>
        <BottomNav />
      </body>
    </html>
  );
}
```

- [ ] **Step 6: Crear las páginas stub**

`app/page.tsx`:

```tsx
import Link from 'next/link';

export default function EntrenarPage() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Entrenar</h1>
      <p className="text-muted-foreground">El registro de entrenos llega en la Fase 2.</p>
      <Link href="/ejercicios" className="text-primary underline">
        Ver catálogo de ejercicios
      </Link>
    </div>
  );
}
```

`app/rutinas/page.tsx`:

```tsx
export default function RutinasPage() {
  return <h1 className="text-2xl font-bold">Rutinas</h1>;
}
```

`app/progreso/page.tsx`:

```tsx
export default function ProgresoPage() {
  return <h1 className="text-2xl font-bold">Progreso</h1>;
}
```

`app/historial/page.tsx`:

```tsx
export default function HistorialPage() {
  return <h1 className="text-2xl font-bold">Historial</h1>;
}
```

- [ ] **Step 7: Verificar build**

Run: `npm run build`
Expected: build sin errores.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: app shell with bottom navigation and stub tabs"
```

---

## Task 9: Pantalla del catálogo de ejercicios (lista + búsqueda)

**Files:**
- Create: `components/exercise-list.tsx`
- Create: `app/ejercicios/page.tsx`
- Create: `components/db-provider.tsx` (siembra el catálogo en el arranque del cliente)
- Test: `components/exercise-list.test.tsx`

- [ ] **Step 1: Escribir el test que falla**

`exercise-list.test.tsx` renderiza la lista con datos en la BD falsa y comprueba agrupación y filtrado.

Crea `components/exercise-list.test.tsx`:

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { createExercise } from '@/lib/repositories/exercises';
import { ExerciseList } from '@/components/exercise-list';

describe('ExerciseList', () => {
  beforeEach(async () => {
    await db.exercises.clear();
    await createExercise({ nombre: 'Press de banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto' });
    await createExercise({ nombre: 'Curl martillo', grupoMuscular: 'biceps', equipamiento: 'mancuerna', tipo: 'aislamiento' });
  });

  it('muestra los ejercicios y sus grupos musculares', async () => {
    render(<ExerciseList />);
    expect(await screen.findByText('Press de banca')).toBeInTheDocument();
    expect(screen.getByText('Curl martillo')).toBeInTheDocument();
    expect(screen.getByText('Pecho')).toBeInTheDocument();
    expect(screen.getByText('Bíceps')).toBeInTheDocument();
  });

  it('filtra por el texto de búsqueda', async () => {
    render(<ExerciseList />);
    await screen.findByText('Press de banca');
    await userEvent.type(screen.getByPlaceholderText('Buscar ejercicio…'), 'curl');
    await waitFor(() => {
      expect(screen.queryByText('Press de banca')).not.toBeInTheDocument();
      expect(screen.getByText('Curl martillo')).toBeInTheDocument();
    });
  });
});
```

- [ ] **Step 2: Ejecutar para verificar que falla**

Run: `npx vitest run components/exercise-list.test.tsx`
Expected: FAIL (no existe el componente).

- [ ] **Step 3: Implementar `components/exercise-list.tsx`**

```tsx
'use client';

import { useMemo, useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import type { Exercise, MuscleGroup } from '@/lib/db/types';
import { MUSCLE_GROUPS } from '@/lib/db/types';
import { muscleGroupLabel, equipmentLabel } from '@/lib/labels';
import { Input } from '@/components/ui/input';

export function ExerciseList() {
  const [query, setQuery] = useState('');

  const exercises = useLiveQuery(async () => {
    const all = await db.exercises.orderBy('nombre').toArray();
    return all.filter((e) => e.deletedAt === null);
  }, []);

  const grouped = useMemo(() => {
    const list = (exercises ?? []).filter((e) =>
      e.nombre.toLowerCase().includes(query.trim().toLowerCase()),
    );
    const map = new Map<MuscleGroup, Exercise[]>();
    for (const ex of list) {
      const arr = map.get(ex.grupoMuscular) ?? [];
      arr.push(ex);
      map.set(ex.grupoMuscular, arr);
    }
    return MUSCLE_GROUPS.filter((g) => map.has(g)).map((g) => ({
      grupo: g,
      items: map.get(g)!,
    }));
  }, [exercises, query]);

  if (exercises === undefined) {
    return <p className="text-muted-foreground">Cargando…</p>;
  }

  return (
    <div className="space-y-4">
      <Input
        placeholder="Buscar ejercicio…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      {grouped.length === 0 && (
        <p className="text-muted-foreground">No hay ejercicios.</p>
      )}
      {grouped.map(({ grupo, items }) => (
        <section key={grupo} className="space-y-1">
          <h2 className="text-xs font-semibold uppercase text-muted-foreground">
            {muscleGroupLabel[grupo]}
          </h2>
          <ul className="divide-y rounded-md border">
            {items.map((ex) => (
              <li key={ex.id} className="flex items-center justify-between p-3">
                <span>{ex.nombre}</span>
                <span className="text-xs text-muted-foreground">
                  {equipmentLabel[ex.equipamiento]}
                </span>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
```

- [ ] **Step 4: Ejecutar para verificar que pasa**

Run: `npx vitest run components/exercise-list.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 5: Crear `components/db-provider.tsx` (siembra en el arranque del cliente)**

```tsx
'use client';

import { useEffect } from 'react';
import { seedCatalogIfEmpty } from '@/lib/db/seed';

export function DbProvider({ children }: { children: React.ReactNode }) {
  useEffect(() => {
    void seedCatalogIfEmpty();
  }, []);
  return <>{children}</>;
}
```

- [ ] **Step 6: Envolver el layout con `DbProvider`**

En `app/layout.tsx`, importa `DbProvider` y envuelve el `<main>`:

```tsx
import { DbProvider } from '@/components/db-provider';
// ...
<body className="min-h-screen pb-16">
  <DbProvider>
    <main className="mx-auto max-w-md p-4">{children}</main>
  </DbProvider>
  <BottomNav />
</body>
```

- [ ] **Step 7: Crear la página `app/ejercicios/page.tsx`**

```tsx
import Link from 'next/link';
import { ExerciseList } from '@/components/exercise-list';

export default function EjerciciosPage() {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Ejercicios</h1>
        <Link
          href="/ejercicios/nuevo"
          className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground"
        >
          Nuevo
        </Link>
      </div>
      <ExerciseList />
    </div>
  );
}
```

- [ ] **Step 8: Verificar build**

Run: `npm run build`
Expected: build sin errores (la ruta `/ejercicios/nuevo` la creamos en la Task 10; el enlace no rompe el build).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: exercise catalog screen with search and grouping + first-run seeding"
```

---

## Task 10: Formulario crear/editar ejercicio + borrado

**Files:**
- Create: `components/exercise-form.tsx`
- Create: `app/ejercicios/nuevo/page.tsx`
- Create: `app/ejercicios/[id]/page.tsx` (editar/borrar)
- Modify: `components/exercise-list.tsx` (enlazar cada item a su edición)
- Test: `components/exercise-form.test.tsx`

- [ ] **Step 1: Escribir el test que falla**

Crea `components/exercise-form.test.tsx`:

```tsx
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { ExerciseForm } from '@/components/exercise-form';

describe('ExerciseForm (crear)', () => {
  beforeEach(async () => {
    await db.exercises.clear();
  });

  it('crea un ejercicio al enviar y llama onSaved', async () => {
    const onSaved = vi.fn();
    render(<ExerciseForm onSaved={onSaved} />);

    await userEvent.type(screen.getByLabelText('Nombre'), 'Face pull');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const all = await db.exercises.toArray();
    expect(all.map((e) => e.nombre)).toContain('Face pull');
  });

  it('no guarda si el nombre está vacío', async () => {
    const onSaved = vi.fn();
    render(<ExerciseForm onSaved={onSaved} />);
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }));
    expect(onSaved).not.toHaveBeenCalled();
    expect(await db.exercises.count()).toBe(0);
  });
});
```

- [ ] **Step 2: Ejecutar para verificar que falla**

Run: `npx vitest run components/exercise-form.test.tsx`
Expected: FAIL (no existe el componente).

- [ ] **Step 3: Implementar `components/exercise-form.tsx`**

```tsx
'use client';

import { useState } from 'react';
import type { Exercise, MuscleGroup, Equipment, ExerciseType } from '@/lib/db/types';
import { MUSCLE_GROUPS, EQUIPMENTS, EXERCISE_TYPES } from '@/lib/db/types';
import { muscleGroupLabel, equipmentLabel, exerciseTypeLabel } from '@/lib/labels';
import { createExercise, updateExercise } from '@/lib/repositories/exercises';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';

export function ExerciseForm({
  existing,
  onSaved,
}: {
  existing?: Exercise;
  onSaved: () => void;
}) {
  const [nombre, setNombre] = useState(existing?.nombre ?? '');
  const [grupoMuscular, setGrupo] = useState<MuscleGroup>(existing?.grupoMuscular ?? 'pecho');
  const [equipamiento, setEquip] = useState<Equipment>(existing?.equipamiento ?? 'barra');
  const [tipo, setTipo] = useState<ExerciseType>(existing?.tipo ?? 'compuesto');
  const [videoUrl, setVideoUrl] = useState(existing?.videoUrl ?? '');
  const [notas, setNotas] = useState(existing?.notas ?? '');
  const [error, setError] = useState('');

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (nombre.trim() === '') {
      setError('El nombre es obligatorio');
      return;
    }
    const data = {
      nombre: nombre.trim(),
      grupoMuscular,
      equipamiento,
      tipo,
      videoUrl: videoUrl.trim() || undefined,
      notas: notas.trim() || undefined,
    };
    if (existing) {
      await updateExercise(existing.id, data);
    } else {
      await createExercise(data);
    }
    onSaved();
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="space-y-1">
        <Label htmlFor="nombre">Nombre</Label>
        <Input id="nombre" value={nombre} onChange={(e) => setNombre(e.target.value)} />
      </div>

      <div className="space-y-1">
        <Label htmlFor="grupo">Grupo muscular</Label>
        <select
          id="grupo"
          className="w-full rounded-md border p-2"
          value={grupoMuscular}
          onChange={(e) => setGrupo(e.target.value as MuscleGroup)}
        >
          {MUSCLE_GROUPS.map((g) => (
            <option key={g} value={g}>{muscleGroupLabel[g]}</option>
          ))}
        </select>
      </div>

      <div className="space-y-1">
        <Label htmlFor="equip">Equipamiento</Label>
        <select
          id="equip"
          className="w-full rounded-md border p-2"
          value={equipamiento}
          onChange={(e) => setEquip(e.target.value as Equipment)}
        >
          {EQUIPMENTS.map((eq) => (
            <option key={eq} value={eq}>{equipmentLabel[eq]}</option>
          ))}
        </select>
      </div>

      <div className="space-y-1">
        <Label htmlFor="tipo">Tipo</Label>
        <select
          id="tipo"
          className="w-full rounded-md border p-2"
          value={tipo}
          onChange={(e) => setTipo(e.target.value as ExerciseType)}
        >
          {EXERCISE_TYPES.map((t) => (
            <option key={t} value={t}>{exerciseTypeLabel[t]}</option>
          ))}
        </select>
      </div>

      <div className="space-y-1">
        <Label htmlFor="video">Vídeo (opcional)</Label>
        <Input id="video" value={videoUrl} onChange={(e) => setVideoUrl(e.target.value)} />
      </div>

      <div className="space-y-1">
        <Label htmlFor="notas">Notas (opcional)</Label>
        <Textarea id="notas" value={notas} onChange={(e) => setNotas(e.target.value)} />
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}
      <Button type="submit">Guardar</Button>
    </form>
  );
}
```

- [ ] **Step 4: Ejecutar para verificar que pasa**

Run: `npx vitest run components/exercise-form.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 5: Crear la página de alta `app/ejercicios/nuevo/page.tsx`**

```tsx
'use client';

import { useRouter } from 'next/navigation';
import { ExerciseForm } from '@/components/exercise-form';

export default function NuevoEjercicioPage() {
  const router = useRouter();
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Nuevo ejercicio</h1>
      <ExerciseForm onSaved={() => router.push('/ejercicios')} />
    </div>
  );
}
```

- [ ] **Step 6: Crear la página de edición `app/ejercicios/[id]/page.tsx`**

Carga el ejercicio por id y permite editar o borrar (lógicamente).

```tsx
'use client';

import { use } from 'react';
import { useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import { softDeleteExercise } from '@/lib/repositories/exercises';
import { ExerciseForm } from '@/components/exercise-form';
import { Button } from '@/components/ui/button';

export default function EditarEjercicioPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  const router = useRouter();
  const exercise = useLiveQuery(() => db.exercises.get(id), [id]);

  if (exercise === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!exercise) return <p>Ejercicio no encontrado.</p>;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Editar ejercicio</h1>
      <ExerciseForm existing={exercise} onSaved={() => router.push('/ejercicios')} />
      <Button
        variant="destructive"
        onClick={async () => {
          await softDeleteExercise(id);
          router.push('/ejercicios');
        }}
      >
        Borrar
      </Button>
    </div>
  );
}
```

- [ ] **Step 7: Enlazar cada item de la lista a su edición**

En `components/exercise-list.tsx`, importa `Link` de `next/link` y envuelve cada `<li>` con un enlace a `/ejercicios/${ex.id}`. Sustituye el bloque `<li>…</li>` por:

```tsx
<li key={ex.id}>
  <Link
    href={`/ejercicios/${ex.id}`}
    className="flex items-center justify-between p-3"
  >
    <span>{ex.nombre}</span>
    <span className="text-xs text-muted-foreground">
      {equipmentLabel[ex.equipamiento]}
    </span>
  </Link>
</li>
```

Y añade arriba del archivo: `import Link from 'next/link';`

- [ ] **Step 8: Verificar tests y build**

Run: `npm test`
Expected: PASS (todos los tests de las tasks 2,4,5,6,8,9,10).
Run: `npm run build`
Expected: build sin errores.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: create/edit/delete custom exercises"
```

---

## Task 11: PWA — manifest y service worker (Serwist)

**Files:**
- Create: `app/manifest.ts`
- Create: `app/sw.ts`
- Modify: `next.config.ts` (o `next.config.mjs`)
- Create iconos: `public/icon-192.png`, `public/icon-512.png`

- [ ] **Step 1: Instalar Serwist**

```bash
npm install @serwist/next && npm install -D serwist
```

- [ ] **Step 2: Crear el manifest `app/manifest.ts`**

```ts
import type { MetadataRoute } from 'next';

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: 'GymLog',
    short_name: 'GymLog',
    description: 'App personal de gimnasio: rutinas, pesos y entrenos',
    start_url: '/',
    display: 'standalone',
    background_color: '#ffffff',
    theme_color: '#0a0a0a',
    icons: [
      { src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
      { src: '/icon-512.png', sizes: '512x512', type: 'image/png' },
    ],
  };
}
```

- [ ] **Step 3: Crear el service worker `app/sw.ts`**

```ts
import { defaultCache } from '@serwist/next/worker';
import { Serwist } from 'serwist';

declare const self: ServiceWorkerGlobalScope;

const serwist = new Serwist({
  precacheEntries: self.__SW_MANIFEST,
  skipWaiting: true,
  clientsClaim: true,
  navigationPreload: true,
  runtimeCaching: defaultCache,
});

serwist.addEventListeners();
```

- [ ] **Step 4: Envolver la config de Next con Serwist**

Reemplaza `next.config.ts` por:

```ts
import withSerwistInit from '@serwist/next';

const withSerwist = withSerwistInit({
  swSrc: 'app/sw.ts',
  swDest: 'public/sw.js',
  disable: process.env.NODE_ENV === 'development',
});

export default withSerwist({});
```

(Si el proyecto generó `next.config.mjs` en lugar de `.ts`, aplica el mismo cambio ahí y borra el duplicado.)

- [ ] **Step 5: Añadir iconos placeholder**

Genera dos PNG cuadrados sólidos (color `#0a0a0a`) como placeholder:

```bash
node -e "const z=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==','base64'); require('fs').writeFileSync('public/icon-192.png',z); require('fs').writeFileSync('public/icon-512.png',z);"
```

(Son placeholders de 1×1 reescalados; sustitúyelos por iconos reales más adelante.)

- [ ] **Step 6: Build de producción y verificación**

Run: `npm run build`
Expected: build sin errores y se genera `public/sw.js`.

- [ ] **Step 7: Comprobación manual de la PWA**

Run: `npm run build && npm run start`
Abre `http://localhost:3000` en el navegador, DevTools → Application:
- Manifest detectado con nombre "GymLog".
- Service Worker registrado y activo.
- En modo offline (DevTools → Network → Offline), recargar sigue mostrando la app y el catálogo (datos en IndexedDB).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: PWA support with manifest and Serwist service worker"
```

---

## Self-Review (cobertura de la spec — Fase 1)

- **PWA instalable + offline** → Tasks 11 (manifest/SW) + Task 4/9 (datos en IndexedDB) ✅
- **Local-first (Dexie/IndexedDB)** → Tasks 4, 5 ✅
- **Catálogo precargado por grupo muscular** → Tasks 6, 9 ✅
- **Crear/editar ejercicios propios** → Task 10 ✅
- **Borrado lógico (tombstone) preparado para sync** → Tasks 3 (`SyncMeta`), 5 (`softDeleteExercise`) ✅
- **Etiquetas/idioma español** → Task 3 (`lib/labels.ts`) ✅
- **Tests de unidad y de componente** → Tasks 2, 5, 6, 8, 9, 10 ✅
- **Diferidos a fases posteriores (correcto):** rutinas, registro, progreso, historial, export/import (Fases 2-3); auth + sync (Fase 4). Las pestañas existen como stubs (Task 8).

Sin placeholders de tipo "TODO/implementar luego": cada paso incluye el código real. Nombres y firmas consistentes entre tasks (`createExercise`, `updateExercise`, `softDeleteExercise`, `seedCatalogIfEmpty`, `db.exercises`).

---

## Notas para fases siguientes

- Fase 4 asignará `userId` a los registros locales con `userId: null` al iniciar sesión, y usará `updatedAt`/`deletedAt` (ya presentes) para el merge LWW.
- El patrón repositorio (`lib/repositories/*`) y los metadatos `SyncMeta` se reutilizan para `Routine`, `WorkoutSession`, etc.
