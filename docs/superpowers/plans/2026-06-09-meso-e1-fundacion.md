# Mesociclos E1 — Fundación + ruta IA (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entidad `Mesocycle` sincronizada + el campo `Routine.mesocycleId` + la ruta `/api/coach/mesociclo` que genera un mesociclo con `generateObject` (DeepSeek, JSON Schema). Sin UI.

**Architecture:** Mismo patrón de entidad sincronizada de D1a/D2a + el wiring del coach (`/api/coach`). La ruta usa `generateObject` (no streaming) con un JSON Schema plano y un prompt puro construido a partir del formulario + snapshot + catálogo.

**Tech Stack:** Dexie v15 · Drizzle/Neon (incl. `jsonb`) · `ai@6` `generateObject` · `@ai-sdk/deepseek` · Clerk · vitest.

**Nota:** Fase **E1** del spec `docs/superpowers/specs/2026-06-09-generador-mesociclos-ia-design.md`. Estado confirmado: Dexie en v14, backup en versión 10, `db/schema.ts` importa `{ pgTable, text, doublePrecision, integer, bigint, boolean, primaryKey }` (FALTA `jsonb`). `generateObject` exportado por `ai`. Reusa `modeloCoach`/`deepseekConfigured` (`lib/coach-model.ts`), `recogerSnapshot`/`CoachSnapshot` (`lib/coach-snapshot.ts`), patrón de `app/api/coach/route.ts`.

---

## File Structure

- **Modify** `lib/db/types.ts` — `Mesocycle`/`SemanaPlan` + `mesocycleId` en `Routine`.
- **Modify** `lib/db/database.ts` — tabla `mesocycles` + `this.version(15)`.
- **Create** `lib/repositories/mesocycles.ts` — CRUD + `semanaActual` (pura).
- **Create** `lib/repositories/mesocycles.test.ts`.
- **Modify** `lib/repositories/routines.ts` — `listStandaloneRoutines`/`listRoutinesByMesocycle`/`setRoutineMesocycle`.
- **Modify** `lib/repositories/routines.test.ts` (o crear si no existe).
- **Modify** `db/schema.ts` — tabla `mesocycles` (jsonb) + columna `mesocycleId` en `routines`.
- **Modify** `lib/sync/server-tables.ts`, `lib/sync/collect.ts`, `lib/sync/apply.ts` (+ tests).
- **Modify** `lib/repositories/backup.ts` (+ test) — versión 11.
- **Create** `scripts/migrate-mesocycles.mjs` — DDL (CREATE mesocycles + ALTER routines).
- **Create** `lib/meso-prompt.ts` — `promptMesociclo` (pura) + `MESO_SCHEMA`.
- **Create** `lib/meso-prompt.test.ts`.
- **Create** `app/api/coach/mesociclo/route.ts` + `route.test.ts`.

---

## Task 1: Entidad `Mesocycle` + Dexie v15 + repo + `semanaActual`

**Files:**
- Modify: `lib/db/types.ts`
- Modify: `lib/db/database.ts`
- Create: `lib/repositories/mesocycles.ts`
- Create: `lib/repositories/mesocycles.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/db/types.ts` (`SyncMeta`, `Routine`), `lib/db/database.ts` (clase, `this.version(14)` con `progressPhotos`), `lib/repositories/body.ts` (idioms `now`/`activo`).

- [ ] **Step 1: Add the entity** — in `lib/db/types.ts`:

```ts
export interface SemanaPlan {
  semana: number;
  descarga: boolean;
  ajuste: string;
}

export interface Mesocycle extends SyncMeta {
  userId: string | null;
  nombre: string;
  objetivo: string;
  semanas: number;
  diasPorSemana: number;
  notas: string | null;
  progresion: SemanaPlan[];
  fechaInicio: number;
}
```
Also add `mesocycleId?: string | null;` to the `Routine` interface.

- [ ] **Step 2: Dexie table** — in `lib/db/database.ts`:
1. Class field: `mesocycles!: Table<Mesocycle, string>;`
2. Add `Mesocycle` to the `import type { … } from './types';`.
3. After `this.version(14)...`:
```ts
    this.version(15).stores({
      mesocycles: 'id, fechaInicio, deletedAt',
    });
```

- [ ] **Step 3: Write the failing test** — create `lib/repositories/mesocycles.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import {
  createMesocycle, getMesocycle, listMesocycles, deleteMesocycle, semanaActual,
} from '@/lib/repositories/mesocycles';
import type { Mesocycle } from '@/lib/db/types';

const DIA = 86400000;
const base = {
  nombre: 'Hipertrofia', objetivo: 'hipertrofia', semanas: 6, diasPorSemana: 4,
  notas: null as string | null, progresion: [{ semana: 1, descarga: false, ajuste: '3x10' }], fechaInicio: 1000,
};

beforeEach(async () => { await db.mesocycles.clear(); });

describe('mesocycles repo', () => {
  it('createMesocycle + get + list', async () => {
    const m = await createMesocycle(base);
    expect(m.id).toBeTruthy();
    expect((await getMesocycle(m.id))?.nombre).toBe('Hipertrofia');
    expect(await listMesocycles()).toHaveLength(1);
  });
  it('deleteMesocycle hace tombstone (list lo excluye)', async () => {
    const m = await createMesocycle(base);
    await deleteMesocycle(m.id);
    expect(await listMesocycles()).toHaveLength(0);
  });
});

describe('semanaActual', () => {
  const meso = { ...base, semanas: 6, fechaInicio: 0 } as unknown as Mesocycle;
  it('antes/al inicio → 1', () => {
    expect(semanaActual(meso, 0)).toBe(1);
    expect(semanaActual(meso, 3 * DIA)).toBe(1);
  });
  it('semana intermedia', () => {
    expect(semanaActual(meso, 8 * DIA)).toBe(2); // día 8 → semana 2
  });
  it('pasado el final → acota a semanas', () => {
    expect(semanaActual(meso, 100 * DIA)).toBe(6);
  });
});
```

- [ ] **Step 4: Run → FAIL** (`npx vitest run lib/repositories/mesocycles.test.ts`).

- [ ] **Step 5: Implement** — create `lib/repositories/mesocycles.ts`:

```ts
import { db } from '@/lib/db/database';
import type { Mesocycle, SemanaPlan } from '@/lib/db/types';

const DIA = 86400000;
const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

export async function createMesocycle(input: {
  nombre: string;
  objetivo: string;
  semanas: number;
  diasPorSemana: number;
  notas: string | null;
  progresion: SemanaPlan[];
  fechaInicio: number;
}): Promise<Mesocycle> {
  const ts = now();
  const m: Mesocycle = { id: crypto.randomUUID(), userId: null, ...input, updatedAt: ts, deletedAt: null };
  await db.mesocycles.put(m);
  return m;
}

export async function getMesocycle(id: string): Promise<Mesocycle | undefined> {
  const m = await db.mesocycles.get(id);
  return m && m.deletedAt === null ? m : undefined;
}

export async function listMesocycles(): Promise<Mesocycle[]> {
  return activo(await db.mesocycles.toArray()).sort((a, b) => b.fechaInicio - a.fechaInicio);
}

export async function deleteMesocycle(id: string): Promise<void> {
  const ts = now();
  await db.mesocycles.update(id, { deletedAt: ts, updatedAt: ts });
}

/** Número de semana 1..semanas calculado desde fechaInicio, acotado. */
export function semanaActual(meso: Mesocycle, ahora: number): number {
  const transcurridas = Math.floor((ahora - meso.fechaInicio) / (7 * DIA));
  return Math.min(meso.semanas, Math.max(1, transcurridas + 1));
}
```

- [ ] **Step 6: Run → PASS** + `npx tsc --noEmit`.

- [ ] **Step 7: Commit**

```bash
git add lib/db/types.ts lib/db/database.ts lib/repositories/mesocycles.ts lib/repositories/mesocycles.test.ts
git commit -m "feat(meso): entidad Mesocycle + Dexie v15 + repo + semanaActual"
```

---

## Task 2: `Routine.mesocycleId` — helpers de repo

**Files:**
- Modify: `lib/repositories/routines.ts`
- Modify/Create: `lib/repositories/routines.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/routines.ts` (`createRoutine`, `listRoutines`, `updateRoutine`, `now`/`activo` idioms) y su test si existe. `Routine.mesocycleId` ya existe en el tipo (Task 1).

- [ ] **Step 1: Write the failing test** — append to `lib/repositories/routines.test.ts` (o crearlo con el setup estándar de fake-indexeddb + `beforeEach` que limpia `db.routines`):

```ts
import { createRoutine, listRoutines, listStandaloneRoutines, listRoutinesByMesocycle, setRoutineMesocycle } from '@/lib/repositories/routines';

it('listStandaloneRoutines excluye las de un mesociclo; byMesocycle las incluye', async () => {
  await db.routines.clear();
  const libre = await createRoutine({ nombre: 'Libre' });
  const dia = await createRoutine({ nombre: 'Push' });
  await setRoutineMesocycle(dia.id, 'meso-1');
  const standalone = await listStandaloneRoutines();
  expect(standalone.map((r) => r.id)).toContain(libre.id);
  expect(standalone.map((r) => r.id)).not.toContain(dia.id);
  const delMeso = await listRoutinesByMesocycle('meso-1');
  expect(delMeso.map((r) => r.id)).toEqual([dia.id]);
});
```
(Adjust imports to the test file's existing `db` import.)

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement** — in `lib/repositories/routines.ts`:

```ts
/** Marca (o desmarca con null) la pertenencia de una rutina a un mesociclo. */
export async function setRoutineMesocycle(routineId: string, mesocycleId: string | null): Promise<void> {
  await db.routines.update(routineId, { mesocycleId, updatedAt: Date.now() });
}

/** Rutinas activas que NO pertenecen a ningún mesociclo. */
export async function listStandaloneRoutines(): Promise<Routine[]> {
  return (await listRoutines()).filter((r) => !r.mesocycleId);
}

/** Rutinas activas de un mesociclo, en orden. */
export async function listRoutinesByMesocycle(mesocycleId: string): Promise<Routine[]> {
  return (await listRoutines()).filter((r) => r.mesocycleId === mesocycleId);
}
```
(Reuse the file's existing `now()` if present instead of `Date.now()`; match the existing idiom. `listRoutines` already returns active routines in order.)

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/routines.ts lib/repositories/routines.test.ts
git commit -m "feat(meso): Routine.mesocycleId + helpers standalone/byMesocycle"
```

---

## Task 3: Drizzle — tabla `mesocycles` + columna `routines.mesocycle_id` + SERVER_TABLES

**Files:**
- Modify: `db/schema.ts`
- Modify: `lib/sync/server-tables.ts`

- [ ] **Step 0: READ FIRST**

Read `db/schema.ts` (import de `drizzle-orm/pg-core` — falta `jsonb`; el helper `sync`; tabla `routines` y `bodyMetrics`/`progressPhotos`), `lib/sync/server-tables.ts`.

- [ ] **Step 1: Add `jsonb` to the import** — in `db/schema.ts`:
```ts
import { pgTable, text, doublePrecision, integer, bigint, boolean, jsonb, primaryKey } from 'drizzle-orm/pg-core';
```

- [ ] **Step 2: Add column to `routines`** — add to the `routines` pgTable definition:
```ts
  mesocycleId: text('mesocycle_id'),
```

- [ ] **Step 3: Add the `mesocycles` table** — after `progressPhotos`:
```ts
export const mesocycles = pgTable('mesocycles', {
  ...sync,
  nombre: text('nombre').notNull(),
  objetivo: text('objetivo').notNull(),
  semanas: integer('semanas').notNull(),
  diasPorSemana: integer('dias_por_semana').notNull(),
  notas: text('notas'),
  progresion: jsonb('progresion').notNull(),
  fechaInicio: bigint('fecha_inicio', { mode: 'number' }).notNull(),
});
```

- [ ] **Step 4: Register in SERVER_TABLES** — in `lib/sync/server-tables.ts`:
```ts
  mesocycles: schema.mesocycles,
```

- [ ] **Step 5: Typecheck** — `npx tsc --noEmit` clean.

- [ ] **Step 6: Commit**

```bash
git add db/schema.ts lib/sync/server-tables.ts
git commit -m "feat(meso): tabla mesocycles + routines.mesocycle_id + SERVER_TABLES"
```

---

## Task 4: collect/apply `mesocycles`

**Files:**
- Modify: `lib/sync/collect.ts`, `lib/sync/apply.ts`
- Modify: `lib/sync/apply.test.ts`, `lib/sync/collect.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/sync/collect.ts`/`apply.ts` (idiom `asSync`, entradas de `progressPhotos`) y los dos tests (idiom `bodyMetrics`/`progressPhotos` con `as unknown as SyncMeta[]`).

- [ ] **Step 1: Write failing tests.**

Append to `lib/sync/apply.test.ts`:
```ts
it('mesocycles se aplican por id (LWW)', async () => {
  await db.mesocycles.clear();
  await applyIncoming([{ table: 'mesocycles', records: [
    { id: 'me1', userId: 'u1', nombre: 'H', objetivo: 'hipertrofia', semanas: 6, diasPorSemana: 4, notas: null, progresion: [], fechaInicio: 1, updatedAt: 100, deletedAt: null },
  ] as unknown as import('@/lib/db/types').SyncMeta[] }]);
  expect((await db.mesocycles.get('me1'))?.nombre).toBe('H');
});
```
Append to `lib/sync/collect.test.ts`:
```ts
it('sincroniza mesocycles', async () => {
  await db.mesocycles.clear();
  await db.mesocycles.put({ id: 'me-c1', userId: null, nombre: 'H', objetivo: 'fuerza', semanas: 4, diasPorSemana: 3, notas: null, progresion: [], fechaInicio: 1, updatedAt: 1000, deletedAt: null });
  const changes = await collectDirty(0);
  expect(changes.find((c) => c.table === 'mesocycles')?.records).toHaveLength(1);
});
```

- [ ] **Step 2: Run → FAIL** (`npx vitest run lib/sync/`).

- [ ] **Step 3:** `apply.ts` → add `mesocycles: asSync(db.mesocycles),` to `TABLE_BY_NAME`.
- [ ] **Step 4:** `collect.ts` → add `{ name: 'mesocycles', table: asSync(db.mesocycles) },` to `SYNCABLE_TABLES` (no `shouldSync`).

- [ ] **Step 5: Run → PASS** + `npx tsc --noEmit`.

- [ ] **Step 6: Commit**

```bash
git add lib/sync/collect.ts lib/sync/apply.ts lib/sync/apply.test.ts lib/sync/collect.test.ts
git commit -m "feat(sync): mesocycles en collect/apply"
```

---

## Task 5: Backup `mesocycles` (versión 11)

**Files:**
- Modify: `lib/repositories/backup.ts`, `lib/repositories/backup.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/backup.ts` (versión 10; `progressPhotos` añadido en todos los puntos).

- [ ] **Step 1: Write the failing test** — append to `lib/repositories/backup.test.ts`:

```ts
it('exporta e importa mesocycles', async () => {
  await db.mesocycles.clear();
  await db.mesocycles.put({ id: 'mb1', userId: null, nombre: 'H', objetivo: 'general', semanas: 5, diasPorSemana: 3, notas: null, progresion: [{ semana: 1, descarga: false, ajuste: 'x' }], fechaInicio: 5, updatedAt: 5, deletedAt: null });
  const backup = await exportData();
  expect(backup.data.mesocycles).toHaveLength(1);
  await db.mesocycles.clear();
  await importData(backup);
  expect((await db.mesocycles.get('mb1'))?.nombre).toBe('H');
});
```

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement** — in `lib/repositories/backup.ts`:
1. Add `Mesocycle` to the `import type { … }`.
2. `BackupFile.data`: add `mesocycles: Mesocycle[];`
3. Bump `version: 10` → `version: 11`.
4. export: `mesocycles: await db.mesocycles.toArray(),`
5. import: add `db.mesocycles` to the tables tuple + `if (d.mesocycles?.length) await db.mesocycles.bulkPut(d.mesocycles);`
6. Fix any hand-rolled `BackupFile` fixture (`mesocycles: []`).

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/backup.ts lib/repositories/backup.test.ts
git commit -m "feat(backup): incluir mesocycles (version 11)"
```

---

## Task 6: DDL Neon `migrate-mesocycles.mjs`

**Files:**
- Create: `scripts/migrate-mesocycles.mjs`

- [ ] **Step 0: READ FIRST**

Read `scripts/migrate-progress-photos.mjs` (loader env, conexión, `CREATE TABLE IF NOT EXISTS`, verificación de columnas).

- [ ] **Step 1: Create `scripts/migrate-mesocycles.mjs`** — mirror del de progress-photos, con DOS sentencias:
```sql
CREATE TABLE IF NOT EXISTS mesocycles (
  id text PRIMARY KEY,
  user_id text NOT NULL,
  updated_at bigint NOT NULL,
  deleted_at bigint,
  server_updated_at bigint NOT NULL,
  nombre text NOT NULL,
  objetivo text NOT NULL,
  semanas integer NOT NULL,
  dias_por_semana integer NOT NULL,
  notas text,
  progresion jsonb NOT NULL,
  fecha_inicio bigint NOT NULL
);
```
y después:
```sql
ALTER TABLE routines ADD COLUMN IF NOT EXISTS mesocycle_id text;
```
Ejecuta ambas con `await sql\`…\`` (dos llamadas separadas). Verifica columnas de `mesocycles` con `information_schema.columns` como el de referencia, y también que `routines.mesocycle_id` existe.

- [ ] **Step 2: Syntax-check only** — `node --check scripts/migrate-mesocycles.mjs` (NO ejecutar contra Neon).

- [ ] **Step 3: Commit**

```bash
git add scripts/migrate-mesocycles.mjs
git commit -m "chore(db): script DDL Neon mesocycles + routines.mesocycle_id"
```

---

## Task 7: `promptMesociclo` (puro) + `MESO_SCHEMA`

**Files:**
- Create: `lib/meso-prompt.ts`
- Create: `lib/meso-prompt.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/coach-prompt.ts` (cómo serializa el snapshot y la persona), `lib/coach-snapshot.ts` (`CoachSnapshot`). El spec define el `MESO_SCHEMA` (JSON Schema) y los parámetros.

- [ ] **Step 1: Write the failing test** — create `lib/meso-prompt.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { promptMesociclo, MESO_SCHEMA } from '@/lib/meso-prompt';
import type { CoachSnapshot } from '@/lib/coach-snapshot';

const snap: CoachSnapshot = {
  estancados: [{ ejercicio: 'Press banca', sesionesSinMejora: 4 }],
  semana: { sesiones: 2, objetivo: 4, volumen: 1000, deltaPct: null, prs: [] },
  grupos: [{ grupo: 'Pecho', volumenSemana: 500, diasSinEntrenar: 3, objetivo: null }],
  cuerpo: { peso: null, medidas: [] },
};

describe('promptMesociclo', () => {
  it('incluye objetivo, días, semanas y referencia catálogo + estancados', () => {
    const p = promptMesociclo(
      { objetivo: 'hipertrofia', diasPorSemana: 4, semanas: 6, minutosPorSesion: 60 },
      snap,
      [{ nombre: 'Sentadilla', grupo: 'cuadriceps', equipamiento: 'barra' }],
    );
    expect(p).toContain('hipertrofia');
    expect(p).toContain('4'); // días
    expect(p).toContain('6'); // semanas
    expect(p).toContain('Sentadilla');
    expect(p.toLowerCase()).toContain('press banca'); // del snapshot (estancado)
  });
});

describe('MESO_SCHEMA', () => {
  it('exige dias y progresion', () => {
    expect(MESO_SCHEMA.required).toContain('dias');
    expect(MESO_SCHEMA.required).toContain('progresion');
  });
});
```

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement** — create `lib/meso-prompt.ts`:

```ts
import type { CoachSnapshot } from '@/lib/coach-snapshot';

export interface MesoParams {
  objetivo: string;
  diasPorSemana: number;
  semanas: number;
  minutosPorSesion: number;
}

export const MESO_SCHEMA = {
  type: 'object',
  properties: {
    nombre: { type: 'string' },
    objetivo: { type: 'string' },
    semanas: { type: 'number' },
    diasPorSemana: { type: 'number' },
    notas: { type: 'string' },
    progresion: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          semana: { type: 'number' },
          descarga: { type: 'boolean' },
          ajuste: { type: 'string' },
        },
        required: ['semana', 'descarga', 'ajuste'],
        additionalProperties: false,
      },
    },
    dias: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          nombre: { type: 'string' },
          orden: { type: 'number' },
          ejercicios: {
            type: 'array',
            items: {
              type: 'object',
              properties: {
                nombre: { type: 'string' },
                grupoMuscular: { type: 'string' },
                equipamiento: { type: 'string' },
                tipo: { type: 'string' },
                seriesObjetivo: { type: 'number' },
                repsObjetivo: { type: 'number' },
                descansoSegundos: { type: 'number' },
                nuevo: { type: 'boolean' },
              },
              required: ['nombre', 'grupoMuscular', 'equipamiento', 'tipo', 'seriesObjetivo', 'repsObjetivo', 'descansoSegundos', 'nuevo'],
              additionalProperties: false,
            },
          },
        },
        required: ['nombre', 'orden', 'ejercicios'],
        additionalProperties: false,
      },
    },
  },
  required: ['nombre', 'objetivo', 'semanas', 'diasPorSemana', 'progresion', 'dias'],
  additionalProperties: false,
} as const;

/** Prompt para generar el mesociclo. Puro: parámetros + snapshot + catálogo. */
export function promptMesociclo(params: MesoParams, snapshot: CoachSnapshot, catalogo: { nombre: string; grupo: string; equipamiento: string }[]): string {
  const lista = catalogo.map((e) => `- ${e.nombre} (${e.grupo}, ${e.equipamiento})`).join('\n');
  return [
    'Eres un entrenador de fuerza experto. Diseña un MESOCICLO de entrenamiento.',
    `Objetivo: ${params.objetivo}. Días por semana: ${params.diasPorSemana}. Duración del mesociclo: ${params.semanas} semanas. Tiempo por sesión: ~${params.minutosPorSesion} min.`,
    '',
    'Reglas:',
    `- Crea exactamente ${params.diasPorSemana} días de entrenamiento (un split coherente).`,
    `- La progresión debe cubrir las ${params.semanas} semanas (incluye una semana de descarga si procede; márcala con descarga=true y un ajuste de menor volumen).`,
    '- Usa PREFERENTEMENTE ejercicios del catálogo proporcionado (por su nombre exacto). Si necesitas uno que no esté, propónlo con nuevo=true y su grupoMuscular/equipamiento/tipo.',
    '- grupoMuscular ∈ {pecho,espalda,hombros,biceps,triceps,cuadriceps,femoral,gluteo,gemelo,abdomen,antebrazo,otro}. equipamiento ∈ {barra,mancuerna,maquina,polea,peso_corporal,otro}. tipo ∈ {compuesto,aislamiento}.',
    '- Ajusta el volumen por grupo teniendo en cuenta los datos del atleta (más volumen donde flojea, atención a los estancados).',
    '- Los targets (series/reps/descanso) son los de la SEMANA 1 (base); la progresión semanal va en "progresion[].ajuste" como texto breve.',
    '',
    'Datos del atleta (JSON):',
    JSON.stringify(snapshot),
    '',
    'Catálogo de ejercicios disponibles:',
    lista || '(vacío)',
  ].join('\n');
}
```

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit` + `npx eslint lib/meso-prompt.ts`.

- [ ] **Step 5: Commit**

```bash
git add lib/meso-prompt.ts lib/meso-prompt.test.ts
git commit -m "feat(meso): promptMesociclo (puro) + MESO_SCHEMA"
```

---

## Task 8: Ruta `/api/coach/mesociclo`

**Files:**
- Create: `app/api/coach/mesociclo/route.ts`
- Create: `app/api/coach/mesociclo/route.test.ts`

- [ ] **Step 0: READ FIRST**

Read `app/api/coach/route.ts` (auth 401, `deepseekConfigured()` 503, `maxDuration`, cómo importa de `ai` y `@/lib/coach-model`), `lib/meso-prompt.ts`. Verifica en `node_modules/ai/docs/` (o `dist/index.d.ts`) la firma de `generateObject` y si el provider DeepSeek necesita `mode`/opciones para salida JSON — sigue AGENTS.md: lee los docs antes de asumir. Si `generateObject({ model, schema, prompt })` no produce JSON con deepseek-chat, prueba la opción documentada (p.ej. `output: 'object'` ya es el default; si hace falta forzar JSON, usa lo que indiquen los docs del provider).

- [ ] **Step 1: Write the failing tests** — create `app/api/coach/mesociclo/route.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest';

const auth = vi.fn();
vi.mock('@clerk/nextjs/server', () => ({ auth: () => auth() }));

const generateObject = vi.fn();
vi.mock('ai', () => ({ generateObject: (...a: unknown[]) => generateObject(...a) }));

const deepseekConfigured = vi.fn(() => true);
vi.mock('@/lib/coach-model', () => ({
  modeloCoach: () => ({}),
  deepseekConfigured: () => deepseekConfigured(),
}));

import { POST } from '@/app/api/coach/mesociclo/route';

const body = {
  params: { objetivo: 'hipertrofia', diasPorSemana: 4, semanas: 6, minutosPorSesion: 60 },
  snapshot: { estancados: [], semana: { sesiones: 0, objetivo: 4, volumen: 0, deltaPct: null, prs: [] }, grupos: [], cuerpo: { peso: null, medidas: [] } },
  catalogo: [{ nombre: 'Sentadilla', grupo: 'cuadriceps', equipamiento: 'barra' }],
};
const req = (b: unknown) => new Request('http://localhost/api/coach/mesociclo', { method: 'POST', body: JSON.stringify(b), headers: { 'content-type': 'application/json' } });

beforeEach(() => { auth.mockReset(); generateObject.mockReset(); deepseekConfigured.mockReturnValue(true); });

describe('POST /api/coach/mesociclo', () => {
  it('401 sin sesión', async () => {
    auth.mockResolvedValue({ userId: null });
    expect((await POST(req(body))).status).toBe(401);
  });
  it('503 sin DeepSeek', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    deepseekConfigured.mockReturnValue(false);
    expect((await POST(req(body))).status).toBe(503);
  });
  it('200 devuelve el objeto generado', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    generateObject.mockResolvedValue({ object: { nombre: 'Plan', dias: [], progresion: [] } });
    const res = await POST(req(body));
    expect(res.status).toBe(200);
    expect((await res.json()).nombre).toBe('Plan');
  });
});
```

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement** — create `app/api/coach/mesociclo/route.ts`:

```ts
import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { generateObject } from 'ai';
import { modeloCoach, deepseekConfigured } from '@/lib/coach-model';
import { promptMesociclo, MESO_SCHEMA, type MesoParams } from '@/lib/meso-prompt';
import type { CoachSnapshot } from '@/lib/coach-snapshot';

export const maxDuration = 120;

export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  if (!deepseekConfigured()) return new NextResponse('Coach no disponible', { status: 503 });

  let payload: { params: MesoParams; snapshot: CoachSnapshot; catalogo: { nombre: string; grupo: string; equipamiento: string }[] };
  try {
    payload = await req.json();
  } catch {
    return new NextResponse('Petición inválida', { status: 400 });
  }
  if (!payload?.params || !payload?.snapshot) {
    return new NextResponse('Petición inválida', { status: 400 });
  }

  const prompt = promptMesociclo(payload.params, payload.snapshot, payload.catalogo ?? []);
  try {
    const { object } = await generateObject({
      model: modeloCoach(),
      schema: MESO_SCHEMA,
      prompt,
    });
    return NextResponse.json(object);
  } catch {
    return new NextResponse('No se pudo generar el mesociclo', { status: 502 });
  }
}
```

If reading the `ai` docs shows `generateObject` requires a different schema shape than a raw JSON Schema literal (e.g. it expects a Zod schema or a `jsonSchema()` wrapper), adapt: import `jsonSchema` from `ai` and wrap `MESO_SCHEMA` (`schema: jsonSchema(MESO_SCHEMA)`). Verify against `node_modules/ai/dist/index.d.ts` and apply the form that typechecks and the test exercises (the test mocks `generateObject`, so the wrapper choice only needs to typecheck + run).

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit` + `npx eslint app/api/coach/mesociclo/route.ts`.

- [ ] **Step 5: Commit**

```bash
git add app/api/coach/mesociclo/route.ts app/api/coach/mesociclo/route.test.ts
git commit -m "feat(meso): ruta /api/coach/mesociclo (generateObject)"
```

---

## Task 9: Verificación final

- [ ] **Step 1:** `npx vitest run && npx tsc --noEmit && npm run lint && npm run build`
Expected: todo verde; ruta `/api/coach/mesociclo` presente en el build.

---

## Self-Review (hecho)

- **Spec cobertura (E1):** entidad `Mesocycle` (incl. `progresion` jsonb) ✓; `Routine.mesocycleId` + helpers ✓; Dexie v15 ✓; sync 3 registros sin shouldSync ✓; backup v11 ✓; DDL (CREATE mesocycles + ALTER routines) ✓; `semanaActual` puro acotado ✓; ruta `/api/coach/mesociclo` con `generateObject` + `MESO_SCHEMA` + `promptMesociclo` (401/503/400/200/502) ✓.
- **Tipos consistentes:** `Mesocycle`/`SemanaPlan`/`MesoParams` reutilizados; `MESO_SCHEMA` casa con el flujo de guardado de E2; repo `now`/`activo` como en el resto.
- **Riesgo señalado:** forma exacta del schema en `generateObject` con DeepSeek → verificar docs `ai@6` (posible `jsonSchema()` wrapper); el test mockea `generateObject` así que el contrato de la ruta queda cubierto.
- **Sin placeholders:** todo el código presente. La UI (formulario/revisión/guardado/vista) es E2/E3.
