# GymLog — Fase 4A: Motor de sincronización (sin credenciales) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir el núcleo de sincronización local-first, testeable en aislamiento sin servidor ni credenciales: recogida de cambios por marca de agua (`updatedAt`), aplicación con *última escritura gana* (LWW), orquestación `runSync` con un transporte inyectable, y el esquema Drizzle + resolutor de servidor (puros).

**Architecture:** El cliente ya sella `updatedAt`/`deletedAt` en cada registro. El sync usa **dos marcas de agua** en una tabla `syncState` de Dexie: `pushWatermark` (último `updatedAt` propio enviado) y `pullCursor` (último `serverUpdatedAt` recibido). **Push:** recoge registros con `updatedAt > pushWatermark` y los envía. **Pull:** pide cambios del servidor con `serverUpdatedAt >= pullCursor` y los fusiona con LWW por `updatedAt`. El **transporte** (HTTP real a `/api/sync/*`) se define como interfaz e se inyecta, así la orquestación se testea con un transporte falso. El `serverUpdatedAt` (reloj del servidor) sólo se usa como cursor de pull; el LWW siempre compara el `updatedAt` del registro (reloj del cliente). Decisión válida para un único usuario en sus dispositivos.

**Tech Stack:** Next.js 16 · TS · Dexie · Drizzle ORM + @neondatabase/serverless (sólo definición de esquema aquí) · Vitest + fake-indexeddb.

**Spec:** `docs/superpowers/specs/2026-05-23-gymlog-design.md` · **Fases previas:** 1, 2A, 2B, 3.

**Fuera de alcance (Fase 4B, necesita credenciales):** endpoints `/api/sync/push|pull` reales, conexión a Neon, migraciones Drizzle, Clerk (login + middleware + `userId`), transporte HTTP real, indicador de estado en UI, despliegue en Vercel.

---

## File Structure

- `lib/db/types.ts` — añadir `SyncState`
- `lib/db/database.ts` — `version(4)` con tabla `syncState`
- `lib/sync/types.ts` — `TableChanges`, `SyncTransport`
- `lib/sync/state.ts` — `getSyncValue`/`setSyncValue` (marcas de agua)
- `lib/sync/collect.ts` — `SYNCABLE_TABLES` + `collectDirty`
- `lib/sync/apply.ts` — `pickWinner` + `applyIncoming` (LWW en Dexie)
- `lib/sync/sync.ts` — `runSync(transport)`
- `db/schema.ts` — esquema Drizzle de las 7 tablas (servidor)
- `lib/sync/server-merge.ts` — `resolveServerWrite` (LWW puro del servidor)
- `drizzle.config.ts` — config de drizzle-kit (para 4B)
- Tests `*.test.ts` junto al código

---

## Task 1: Dexie v4 (syncState) + marcas de agua + recogida de cambios

**Files:**
- Modify: `lib/db/types.ts`, `lib/db/database.ts`
- Create: `lib/sync/types.ts`, `lib/sync/state.ts`, `lib/sync/collect.ts`
- Test: `lib/sync/collect.test.ts`

- [ ] **Step 1: Añadir tipo en `lib/db/types.ts`**

```ts
export interface SyncState {
  key: string;
  value: number;
}
```

- [ ] **Step 2: Añadir `version(4)` en `lib/db/database.ts`**

Añade la propiedad de tabla y la versión 4 (mantén v1–v3 intactas). En la clase:
```ts
  syncState!: Table<SyncState, string>;
```
(importa `SyncState` junto a los demás tipos). Y tras `this.version(3)...`:
```ts
    this.version(4).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineDayId, exerciseId, orden, deletedAt',
      workoutSessions: 'id, userId, routineDayId, fecha, deletedAt',
      loggedExercises: 'id, sessionId, exerciseId, orden, deletedAt',
      loggedSets: 'id, loggedExerciseId, orden, deletedAt',
      syncState: 'key',
    });
```

- [ ] **Step 3: Crear `lib/sync/types.ts`**

```ts
import type { SyncMeta } from '@/lib/db/types';

export interface TableChanges {
  table: string;
  records: SyncMeta[];
}

export interface SyncTransport {
  push(changes: TableChanges[]): Promise<void>;
  pull(sinceCursor: number): Promise<{ changes: TableChanges[]; cursor: number }>;
}
```

- [ ] **Step 4: Crear `lib/sync/state.ts`**

```ts
import { db } from '@/lib/db/database';

export async function getSyncValue(key: string): Promise<number> {
  const row = await db.syncState.get(key);
  return row?.value ?? 0;
}

export async function setSyncValue(key: string, value: number): Promise<void> {
  await db.syncState.put({ key, value });
}

export const PUSH_WATERMARK = 'pushWatermark';
export const PULL_CURSOR = 'pullCursor';
```

- [ ] **Step 5: Escribir el test que falla en `lib/sync/collect.test.ts`**

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { createExercise } from '@/lib/repositories/exercises';
import { createRoutine } from '@/lib/repositories/routines';
import { collectDirty } from '@/lib/sync/collect';

beforeEach(async () => {
  await Promise.all([db.exercises.clear(), db.routines.clear()]);
});

describe('collectDirty', () => {
  it('recoge registros con updatedAt > marca de agua', async () => {
    await createRoutine({ nombre: 'Vieja' }); // updatedAt ~ahora
    const changes = await collectDirty(0);
    const rutinas = changes.find((c) => c.table === 'routines');
    expect(rutinas?.records).toHaveLength(1);
  });

  it('excluye lo anterior a la marca de agua', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const changes = await collectDirty(r.updatedAt); // > r.updatedAt => nada
    expect(changes.find((c) => c.table === 'routines')).toBeUndefined();
  });

  it('sólo sincroniza ejercicios personalizados, no los del catálogo (seed)', async () => {
    await db.exercises.put({
      id: 'seed-x', userId: null, nombre: 'Seed', grupoMuscular: 'pecho',
      equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: Date.now(), deletedAt: null,
    });
    await createExercise({ nombre: 'Mío', grupoMuscular: 'biceps', equipamiento: 'mancuerna', tipo: 'aislamiento' });
    const changes = await collectDirty(0);
    const ej = changes.find((c) => c.table === 'exercises');
    expect(ej?.records.map((r) => (r as { nombre: string }).nombre)).toEqual(['Mío']);
  });
});
```

- [ ] **Step 6: Ejecutar → FALLA**

Run: `npx vitest run lib/sync/collect.test.ts`

- [ ] **Step 7: Implementar `lib/sync/collect.ts`**

```ts
import type { Table } from 'dexie';
import { db } from '@/lib/db/database';
import type { SyncMeta } from '@/lib/db/types';
import type { TableChanges } from './types';

interface SyncableTable {
  name: string;
  table: Table<SyncMeta, string>;
  shouldSync?: (r: SyncMeta) => boolean;
}

const asSync = (t: unknown) => t as Table<SyncMeta, string>;

export const SYNCABLE_TABLES: SyncableTable[] = [
  { name: 'exercises', table: asSync(db.exercises), shouldSync: (r) => (r as SyncMeta & { esPersonalizado?: boolean }).esPersonalizado === true },
  { name: 'routines', table: asSync(db.routines) },
  { name: 'routineDays', table: asSync(db.routineDays) },
  { name: 'routineExercises', table: asSync(db.routineExercises) },
  { name: 'workoutSessions', table: asSync(db.workoutSessions) },
  { name: 'loggedExercises', table: asSync(db.loggedExercises) },
  { name: 'loggedSets', table: asSync(db.loggedSets) },
];

export async function collectDirty(sinceUpdatedAt: number): Promise<TableChanges[]> {
  const out: TableChanges[] = [];
  for (const { name, table, shouldSync } of SYNCABLE_TABLES) {
    const all = await table.toArray();
    let records = all.filter((r) => r.updatedAt > sinceUpdatedAt);
    if (shouldSync) records = records.filter(shouldSync);
    if (records.length) out.push({ table: name, records });
  }
  return out;
}
```

- [ ] **Step 8: Ejecutar → PASA** · `npx tsc --noEmit` limpio · Commit

```bash
git add -A && git commit -m "feat: sync watermarks (Dexie v4) and dirty-change collection"
```

---

## Task 2: Aplicación LWW (merge en Dexie)

**Files:**
- Create: `lib/sync/apply.ts`
- Test: `lib/sync/apply.test.ts`

- [ ] **Step 1: Escribir los tests que fallan en `lib/sync/apply.test.ts`**

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { pickWinner, applyIncoming } from '@/lib/sync/apply';
import type { SyncMeta } from '@/lib/db/types';

beforeEach(async () => {
  await db.routines.clear();
});

describe('pickWinner (LWW)', () => {
  it('gana el de mayor updatedAt; sin local gana el entrante', () => {
    const a: SyncMeta = { id: '1', updatedAt: 10, deletedAt: null };
    const b: SyncMeta = { id: '1', updatedAt: 20, deletedAt: null };
    expect(pickWinner(a, b)).toBe(b);
    expect(pickWinner(b, a)).toBe(b);
    expect(pickWinner(undefined, a)).toBe(a);
  });
});

describe('applyIncoming', () => {
  it('inserta entrantes nuevos y respeta el más reciente', async () => {
    await db.routines.put({ id: 'r1', userId: null, nombre: 'Local', archivada: false, updatedAt: 100, deletedAt: null });
    await applyIncoming([
      { table: 'routines', records: [
        { id: 'r1', userId: null, nombre: 'RemotoNuevo', archivada: false, updatedAt: 200, deletedAt: null } as unknown as SyncMeta,
        { id: 'r2', userId: null, nombre: 'OtroRemoto', archivada: false, updatedAt: 50, deletedAt: null } as unknown as SyncMeta,
      ] },
    ]);
    expect((await db.routines.get('r1'))?.nombre).toBe('RemotoNuevo');
    expect(await db.routines.get('r2')).toBeDefined();
  });

  it('no pisa un local más reciente que el entrante', async () => {
    await db.routines.put({ id: 'r1', userId: null, nombre: 'LocalNuevo', archivada: false, updatedAt: 300, deletedAt: null });
    await applyIncoming([
      { table: 'routines', records: [
        { id: 'r1', userId: null, nombre: 'RemotoViejo', archivada: false, updatedAt: 100, deletedAt: null } as unknown as SyncMeta,
      ] },
    ]);
    expect((await db.routines.get('r1'))?.nombre).toBe('LocalNuevo');
  });

  it('aplica tombstones (borrados) entrantes', async () => {
    await db.routines.put({ id: 'r1', userId: null, nombre: 'X', archivada: false, updatedAt: 100, deletedAt: null });
    await applyIncoming([
      { table: 'routines', records: [
        { id: 'r1', userId: null, nombre: 'X', archivada: false, updatedAt: 200, deletedAt: 200 } as unknown as SyncMeta,
      ] },
    ]);
    expect((await db.routines.get('r1'))?.deletedAt).toBe(200);
  });
});
```

- [ ] **Step 2: Ejecutar → FALLAN**

Run: `npx vitest run lib/sync/apply.test.ts`

- [ ] **Step 3: Implementar `lib/sync/apply.ts`**

```ts
import type { Table } from 'dexie';
import { db } from '@/lib/db/database';
import type { SyncMeta } from '@/lib/db/types';
import type { TableChanges } from './types';

const asSync = (t: unknown) => t as Table<SyncMeta, string>;

const TABLE_BY_NAME: Record<string, Table<SyncMeta, string>> = {
  exercises: asSync(db.exercises),
  routines: asSync(db.routines),
  routineDays: asSync(db.routineDays),
  routineExercises: asSync(db.routineExercises),
  workoutSessions: asSync(db.workoutSessions),
  loggedExercises: asSync(db.loggedExercises),
  loggedSets: asSync(db.loggedSets),
};

export function pickWinner<T extends SyncMeta>(local: T | undefined, incoming: T): T {
  if (!local) return incoming;
  return incoming.updatedAt >= local.updatedAt ? incoming : local;
}

export async function applyIncoming(changes: TableChanges[]): Promise<void> {
  for (const { table: name, records } of changes) {
    const table = TABLE_BY_NAME[name];
    if (!table) continue;
    for (const incoming of records) {
      const local = await table.get(incoming.id);
      if (pickWinner(local, incoming) === incoming) {
        await table.put(incoming);
      }
    }
  }
}
```

- [ ] **Step 4: Ejecutar → PASAN** · Commit

```bash
git add -A && git commit -m "feat: LWW merge (pickWinner + applyIncoming) for sync pull"
```

---

## Task 3: Orquestación `runSync`

**Files:**
- Create: `lib/sync/sync.ts`
- Test: `lib/sync/sync.test.ts`

- [ ] **Step 1: Escribir los tests que fallan en `lib/sync/sync.test.ts`**

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { db } from '@/lib/db/database';
import { createRoutine } from '@/lib/repositories/routines';
import { runSync } from '@/lib/sync/sync';
import { getSyncValue, PUSH_WATERMARK, PULL_CURSOR } from '@/lib/sync/state';
import type { SyncTransport, TableChanges } from '@/lib/sync/types';
import type { SyncMeta } from '@/lib/db/types';

beforeEach(async () => {
  await Promise.all([db.routines.clear(), db.syncState.clear()]);
});

it('envía los cambios locales sucios y avanza la marca de push', async () => {
  await createRoutine({ nombre: 'Local' });
  let enviado: TableChanges[] = [];
  const transport: SyncTransport = {
    push: vi.fn(async (c) => { enviado = c; }),
    pull: vi.fn(async () => ({ changes: [], cursor: 0 })),
  };
  await runSync(transport);
  expect(transport.push).toHaveBeenCalledTimes(1);
  expect(enviado.find((c) => c.table === 'routines')?.records).toHaveLength(1);
  expect(await getSyncValue(PUSH_WATERMARK)).toBeGreaterThan(0);
});

it('aplica los cambios recibidos del pull y guarda el cursor', async () => {
  const transport: SyncTransport = {
    push: vi.fn(async () => {}),
    pull: vi.fn(async () => ({
      changes: [{ table: 'routines', records: [
        { id: 'remote1', userId: null, nombre: 'Remota', archivada: false, updatedAt: 500, deletedAt: null } as unknown as SyncMeta,
      ] }],
      cursor: 999,
    })),
  };
  await runSync(transport);
  expect(await db.routines.get('remote1')).toBeDefined();
  expect(await getSyncValue(PULL_CURSOR)).toBe(999);
});

it('no llama a push si no hay cambios sucios', async () => {
  const start = Date.now();
  await db.syncState.put({ key: PUSH_WATERMARK, value: start });
  const transport: SyncTransport = {
    push: vi.fn(async () => {}),
    pull: vi.fn(async () => ({ changes: [], cursor: 0 })),
  };
  await runSync(transport);
  expect(transport.push).not.toHaveBeenCalled();
});
```

- [ ] **Step 2: Ejecutar → FALLAN**

Run: `npx vitest run lib/sync/sync.test.ts`

- [ ] **Step 3: Implementar `lib/sync/sync.ts`**

```ts
import type { SyncTransport } from './types';
import { collectDirty } from './collect';
import { applyIncoming } from './apply';
import { getSyncValue, setSyncValue, PUSH_WATERMARK, PULL_CURSOR } from './state';

// Sincroniza: empuja cambios locales y aplica los del servidor (LWW).
export async function runSync(transport: SyncTransport): Promise<void> {
  // PUSH: recoge lo cambiado desde la última marca; sella la marca con el instante de inicio.
  const startTime = Date.now();
  const prevWatermark = await getSyncValue(PUSH_WATERMARK);
  const dirty = await collectDirty(prevWatermark);
  if (dirty.length > 0) {
    await transport.push(dirty);
  }
  await setSyncValue(PUSH_WATERMARK, startTime);

  // PULL: pide lo cambiado en el servidor desde el cursor y lo fusiona.
  const cursor = await getSyncValue(PULL_CURSOR);
  const { changes, cursor: newCursor } = await transport.pull(cursor);
  if (changes.length > 0) {
    await applyIncoming(changes);
  }
  await setSyncValue(PULL_CURSOR, newCursor);
}
```

- [ ] **Step 4: Ejecutar → PASAN** · `npx tsc --noEmit` limpio · Commit

```bash
git add -A && git commit -m "feat: runSync orchestration (push dirty, pull+merge, advance watermarks)"
```

---

## Task 4: Esquema Drizzle (servidor) + resolutor LWW puro

**Files:**
- Create: `db/schema.ts`, `lib/sync/server-merge.ts`, `drizzle.config.ts`
- Test: `lib/sync/server-merge.test.ts`

- [ ] **Step 1: Instalar dependencias (sólo definición; no se conecta a Neon aquí)**

```bash
npm install drizzle-orm @neondatabase/serverless && npm install -D drizzle-kit
```
(Si hay conflicto de peer deps, reintenta con `--legacy-peer-deps`.)

- [ ] **Step 2: Crear `db/schema.ts`** (tablas espejo del cliente; `userId` + `serverUpdatedAt` para el sync)

```ts
import { pgTable, text, doublePrecision, integer, bigint, boolean } from 'drizzle-orm/pg-core';

// Columnas comunes de sincronización en cada tabla.
const sync = {
  id: text('id').primaryKey(),
  userId: text('user_id').notNull(),
  updatedAt: bigint('updated_at', { mode: 'number' }).notNull(), // reloj del cliente (LWW)
  deletedAt: bigint('deleted_at', { mode: 'number' }), // tombstone (nullable)
  serverUpdatedAt: bigint('server_updated_at', { mode: 'number' }).notNull(), // reloj del servidor (cursor de pull)
};

export const exercises = pgTable('exercises', {
  ...sync,
  nombre: text('nombre').notNull(),
  grupoMuscular: text('grupo_muscular').notNull(),
  equipamiento: text('equipamiento').notNull(),
  tipo: text('tipo').notNull(),
  videoUrl: text('video_url'),
  notas: text('notas'),
  esPersonalizado: boolean('es_personalizado').notNull(),
});

export const routines = pgTable('routines', {
  ...sync,
  nombre: text('nombre').notNull(),
  descripcion: text('descripcion'),
  archivada: boolean('archivada').notNull(),
});

export const routineDays = pgTable('routine_days', {
  ...sync,
  routineId: text('routine_id').notNull(),
  nombre: text('nombre').notNull(),
  orden: integer('orden').notNull(),
  notas: text('notas'),
});

export const routineExercises = pgTable('routine_exercises', {
  ...sync,
  routineDayId: text('routine_day_id').notNull(),
  exerciseId: text('exercise_id').notNull(),
  orden: integer('orden').notNull(),
  seriesObjetivo: integer('series_objetivo'),
  repsObjetivo: integer('reps_objetivo'),
  descansoSegundos: integer('descanso_segundos'),
  notas: text('notas'),
});

export const workoutSessions = pgTable('workout_sessions', {
  ...sync,
  routineDayId: text('routine_day_id'),
  fecha: bigint('fecha', { mode: 'number' }).notNull(),
  duracionSegundos: integer('duracion_segundos'),
  notas: text('notas'),
});

export const loggedExercises = pgTable('logged_exercises', {
  ...sync,
  sessionId: text('session_id').notNull(),
  exerciseId: text('exercise_id').notNull(),
  orden: integer('orden').notNull(),
});

export const loggedSets = pgTable('logged_sets', {
  ...sync,
  loggedExerciseId: text('logged_exercise_id').notNull(),
  orden: integer('orden').notNull(),
  peso: doublePrecision('peso').notNull(),
  reps: integer('reps').notNull(),
  esCalentamiento: boolean('es_calentamiento'),
});
```

- [ ] **Step 3: Crear `drizzle.config.ts`** (para las migraciones de la Fase 4B)

```ts
import { defineConfig } from 'drizzle-kit';

export default defineConfig({
  schema: './db/schema.ts',
  out: './db/migrations',
  dialect: 'postgresql',
  dbCredentials: {
    url: process.env.DATABASE_URL ?? '',
  },
});
```

- [ ] **Step 4: Escribir el test que falla en `lib/sync/server-merge.test.ts`**

```ts
import { describe, it, expect } from 'vitest';
import { resolveServerWrite } from '@/lib/sync/server-merge';

describe('resolveServerWrite (LWW del servidor)', () => {
  it('acepta el entrante si es más nuevo o igual', () => {
    expect(resolveServerWrite(100, 200)).toBe(true);
    expect(resolveServerWrite(100, 100)).toBe(true);
  });
  it('rechaza el entrante si el servidor tiene algo más nuevo', () => {
    expect(resolveServerWrite(300, 200)).toBe(false);
  });
  it('acepta si no había nada en el servidor', () => {
    expect(resolveServerWrite(undefined, 50)).toBe(true);
  });
});
```

- [ ] **Step 5: Ejecutar → FALLA**

Run: `npx vitest run lib/sync/server-merge.test.ts`

- [ ] **Step 6: Implementar `lib/sync/server-merge.ts`**

```ts
// ¿Debe el servidor aceptar el registro entrante? LWW por updatedAt del cliente.
export function resolveServerWrite(existingUpdatedAt: number | undefined, incomingUpdatedAt: number): boolean {
  if (existingUpdatedAt === undefined) return true;
  return incomingUpdatedAt >= existingUpdatedAt;
}
```

- [ ] **Step 7: Verificación final**

Run: `npm test` → todo verde.
Run: `npx tsc --noEmit` → sin errores.
Run: `npm run lint` → sin errores ni warnings.
Run: `npm run build` → pasa (el esquema Drizzle no se importa en el bundle de cliente; no debe romper el build).

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: Drizzle server schema + pure server-side LWW resolver"
```

---

## Self-Review (cobertura — Fase 4A)

- **Recogida de cambios por marca de agua (outbox sin tabla extra)** → Task 1 (`collectDirty`) ✅
- **Merge LWW + tombstones** → Tasks 2 (`pickWinner`/`applyIncoming`), 4 (`resolveServerWrite`) ✅
- **Orquestación push/pull con cursor** → Task 3 (`runSync`) ✅
- **Transporte inyectable (testeable sin servidor)** → Tasks 1, 3 (`SyncTransport`) ✅
- **Esquema servidor (Drizzle) listo para migrar en 4B** → Task 4 ✅
- **El catálogo seed no se sincroniza (sólo ejercicios propios)** → Task 1 (`shouldSync`) ✅
- **Diferido a 4B (necesita credenciales):** endpoints reales, Clerk + `userId`, conexión a Neon + migraciones, transporte HTTP, indicador de estado, despliegue.

Sin placeholders. Nombres/firmas consistentes (`collectDirty`, `pickWinner`, `applyIncoming`, `runSync`, `getSyncValue`/`setSyncValue`, `resolveServerWrite`, `PUSH_WATERMARK`, `PULL_CURSOR`).

## Notas para Fase 4B

- El transporte HTTP real implementará `SyncTransport.push` → `POST /api/sync/push` (body = `TableChanges[]`) y `pull` → `GET /api/sync/pull?cursor=` (devuelve `{ changes, cursor }`).
- El endpoint de push usará `resolveServerWrite` por registro y sellará `serverUpdatedAt = Date.now()` en el servidor; el de pull devolverá `serverUpdatedAt >= cursor` y `cursor = max(serverUpdatedAt)`.
- Al iniciar sesión (Clerk), asignar el `userId` real a los registros locales con `userId: null` (excepto seeds) y forzar un `runSync`.
- Disparar `runSync` al cargar, al recuperar conexión (`online`) y tras mutaciones (debounce). Indicador: sincronizado / pendiente / offline.
