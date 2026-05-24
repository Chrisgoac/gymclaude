# Multi-gimnasio Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Etiquetar cada entreno con el gimnasio donde se hizo y poder filtrar todo el análisis (gráfica, PRs, historial, volumen) y el autorrelleno por gimnasio.

**Architecture:** Nueva entidad `Gym` sincronizada (Dexie + Drizzle) y un campo `gymId` en `WorkoutSession`. Las stats filtran sesiones por gimnasio. La obligatoriedad del gimnasio se aplica en la UI (las firmas de repositorio mantienen el parámetro opcional para no romper los 71 tests existentes). El filtro de vista vive en `localStorage` (no se sincroniza).

**Tech Stack:** Next.js 16 (App Router, `--webpack`), React 19, Dexie (IndexedDB), Drizzle + Neon Postgres, Clerk, Tailwind v4 + shadcn (base-nova), Vitest + RTL + fake-indexeddb. Estética Brutalist Iron.

**Spec:** `docs/superpowers/specs/2026-05-24-multi-gimnasio-design.md`

---

## File Structure

**Crear:**
- `lib/repositories/gyms.ts` — CRUD de gimnasios + resolución de nombre.
- `lib/repositories/gyms.test.ts` — tests del repo.
- `lib/gym-filter.ts` — estado del filtro de vista (localStorage) + hook.
- `lib/gym-filter.test.ts` — tests get/set del filtro.
- `components/gym-manager.tsx` — gestión de gimnasios + backfill (en Ajustes).
- `components/gym-manager.test.tsx` — test del gestor.
- `components/gym-filter.tsx` — chips de filtro (Progreso/Historial).
- `components/gym-picker.tsx` — selector de gimnasio al empezar entreno.

**Modificar:**
- `lib/db/types.ts` — interface `Gym`, `gymId` en `WorkoutSession`.
- `lib/db/database.ts` — tabla `gyms`, `version(5)`.
- `lib/db/database.test.ts` — test de la v5.
- `db/schema.ts` — tabla `gyms` + columna `gym_id`.
- `lib/sync/collect.ts`, `lib/sync/apply.ts`, `lib/sync/server-tables.ts` — registrar `gyms`.
- `lib/sync/collect.test.ts` — test de `gyms` en sync.
- `lib/repositories/workouts.ts` — `startSession` con `gymId`, `getLastSet` por gimnasio, backfill.
- `lib/repositories/workouts.test.ts` — tests nuevos.
- `lib/repositories/stats.ts` — filtro `gymId` en las 4 funciones de análisis.
- `lib/repositories/stats.test.ts` — tests del filtro.
- `lib/repositories/backup.ts` — incluir `gyms`.
- `lib/repositories/backup.test.ts` — round-trip de `gyms`.
- `components/start-workout.tsx` (+ `.test.tsx`) — flujo con selector de gimnasio.
- `components/logged-exercise-card.tsx` — pasar `gymId` al autorrelleno.
- `app/entrenar/[sessionId]/page.tsx` — pasar `gymId` a la tarjeta.
- `app/progreso/page.tsx`, `components/exercise-progress.tsx` — filtro por gimnasio.
- `app/historial/page.tsx`, `components/session-summary-list.tsx` — filtro + nombre de gimnasio.
- `app/historial/[sessionId]/page.tsx` — mostrar/cambiar gimnasio del entreno.
- `app/ajustes/page.tsx` — montar `GymManager`.

---

## Task 1: Tipos + entidad Gym + Dexie v5

**Files:**
- Modify: `lib/db/types.ts`
- Modify: `lib/db/database.ts`
- Test: `lib/db/database.test.ts`

- [ ] **Step 1: Añadir el tipo `Gym` y `gymId` en types.ts**

En `lib/db/types.ts`, añade tras la interface `Exercise` (o junto a `Routine`):

```ts
export interface Gym extends SyncMeta {
  userId: string | null;
  nombre: string;
  orden: number;
  archivada: boolean;
}
```

Y en `WorkoutSession`, añade el campo (tras `routineDayId?`):

```ts
  gymId?: string | null; // null/ausente = "Sin gimnasio" (datos pre-migración)
```

- [ ] **Step 2: Escribir el test de la v5 (falla)**

En `lib/db/database.test.ts`, dentro del `describe('GymLogDB', ...)` existente (los imports
`db`, `it`, `expect` ya están en el fichero — no los repitas), añade este test:

```ts
it('v5: tabla gyms operativa y workoutSessions admite gymId', async () => {
  await db.gyms.clear();
  await db.gyms.put({
    id: 'g1', userId: null, nombre: 'Gold\'s', orden: 0, archivada: false,
    updatedAt: Date.now(), deletedAt: null,
  });
  expect((await db.gyms.get('g1'))?.nombre).toBe("Gold's");

  await db.workoutSessions.clear();
  await db.workoutSessions.put({
    id: 's1', userId: null, gymId: 'g1', fecha: Date.now(),
    updatedAt: Date.now(), deletedAt: null,
  });
  const porGym = await db.workoutSessions.where('gymId').equals('g1').toArray();
  expect(porGym.map((s) => s.id)).toEqual(['s1']);
});
```

- [ ] **Step 3: Ejecutar el test (debe fallar)**

Run: `npm run test -- database.test.ts`
Expected: FAIL — `db.gyms` no existe / `gymId` no indexado.

- [ ] **Step 4: Implementar la tabla `gyms` y la versión 5**

En `lib/db/database.ts`:

1. Importa `Gym` en el import de tipos.
2. Declara la tabla en la clase, junto a las demás:

```ts
  gyms!: Table<Gym, string>;
```

3. Añade la versión 5 al final del constructor (después de `version(4)`):

```ts
    this.version(5).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineDayId, exerciseId, orden, deletedAt',
      workoutSessions: 'id, userId, routineDayId, gymId, fecha, deletedAt',
      loggedExercises: 'id, sessionId, exerciseId, orden, deletedAt',
      loggedSets: 'id, loggedExerciseId, orden, deletedAt',
      syncState: 'key',
      gyms: 'id, userId, nombre, deletedAt',
    });
```

- [ ] **Step 5: Ejecutar el test (debe pasar)**

Run: `npm run test -- database.test.ts`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add lib/db/types.ts lib/db/database.ts lib/db/database.test.ts
git commit -m "feat(db): entidad Gym y gymId en sesión (Dexie v5)"
```

---

## Task 2: Esquema Drizzle + columna en Neon

**Files:**
- Modify: `db/schema.ts`

> **Nota de entorno:** `drizzle-kit push` necesita `DATABASE_URL` en `.env.local`. Si falla por credenciales ausentes, primero: `vercel env pull --environment=production --yes .env.local`.

- [ ] **Step 1: Añadir la tabla `gyms` y la columna `gym_id`**

En `db/schema.ts`, añade la columna a `workoutSessions` (tras `routineDayId`):

```ts
  gymId: text('gym_id'),
```

Y añade la tabla nueva al final del fichero:

```ts
export const gyms = pgTable('gyms', {
  ...sync,
  nombre: text('nombre').notNull(),
  orden: integer('orden').notNull(),
  archivada: boolean('archivada').notNull(),
});
```

- [ ] **Step 2: Aplicar el cambio a Neon**

Run: `npm run db:push`
Expected: drizzle-kit crea la tabla `gyms` y la columna `gym_id` en `workout_sessions` sin errores. Acepta los prompts de creación.

- [ ] **Step 3: Verificar tipos**

Run: `npx tsc --noEmit`
Expected: sin errores.

- [ ] **Step 4: Commit**

```bash
git add db/schema.ts
git commit -m "feat(db): tabla gyms y columna gym_id en Postgres (Neon)"
```

---

## Task 3: Registrar `gyms` en el motor de sync

**Files:**
- Modify: `lib/sync/collect.ts`
- Modify: `lib/sync/apply.ts`
- Modify: `lib/sync/server-tables.ts`
- Test: `lib/sync/collect.test.ts`

- [ ] **Step 1: Escribir el test (falla)**

En `lib/sync/collect.test.ts`, dentro del `describe('collectDirty', ...)`, añade:

```ts
  it('sincroniza los gimnasios', async () => {
    await db.gyms.clear();
    await db.gyms.put({
      id: 'g1', userId: null, nombre: 'Gold\'s', orden: 0, archivada: false,
      updatedAt: Date.now(), deletedAt: null,
    });
    const changes = await collectDirty(0);
    expect(changes.find((c) => c.table === 'gyms')?.records).toHaveLength(1);
  });
```

Y en el `beforeEach` del fichero añade `db.gyms.clear()`:

```ts
beforeEach(async () => {
  await Promise.all([db.exercises.clear(), db.routines.clear(), db.gyms.clear()]);
});
```

- [ ] **Step 2: Ejecutar el test (debe fallar)**

Run: `npm run test -- collect.test.ts`
Expected: FAIL — `gyms` no está en `SYNCABLE_TABLES`.

- [ ] **Step 3: Registrar `gyms` en los tres sitios**

En `lib/sync/collect.ts`, añade al final del array `SYNCABLE_TABLES`:

```ts
  { name: 'gyms', table: asSync(db.gyms) },
```

En `lib/sync/apply.ts`, añade al objeto `TABLE_BY_NAME`:

```ts
  gyms: asSync(db.gyms),
```

En `lib/sync/server-tables.ts`, añade al objeto `SERVER_TABLES`:

```ts
  gyms: schema.gyms,
```

- [ ] **Step 4: Ejecutar el test (debe pasar)**

Run: `npm run test -- collect.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add lib/sync/collect.ts lib/sync/apply.ts lib/sync/server-tables.ts lib/sync/collect.test.ts
git commit -m "feat(sync): sincronizar la tabla gyms"
```

---

## Task 4: Repositorio de gimnasios

**Files:**
- Create: `lib/repositories/gyms.ts`
- Test: `lib/repositories/gyms.test.ts`

- [ ] **Step 1: Escribir los tests (fallan)**

Crea `lib/repositories/gyms.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import {
  listGyms, createGym, renameGym, archiveGym, softDeleteGym, reorderGyms, getGymsMap,
} from '@/lib/repositories/gyms';

beforeEach(async () => {
  await db.gyms.clear();
  await db.workoutSessions.clear();
});

describe('repo gyms', () => {
  it('crea con orden incremental y lista activos por orden', async () => {
    const a = await createGym('Gold\'s');
    const b = await createGym('CrossFit');
    expect(a.orden).toBe(0);
    expect(b.orden).toBe(1);
    expect((await listGyms()).map((g) => g.id)).toEqual([a.id, b.id]);
  });

  it('renombra', async () => {
    const g = await createGym('A');
    await renameGym(g.id, 'B');
    expect((await db.gyms.get(g.id))?.nombre).toBe('B');
  });

  it('archivar oculta de listGyms pero conserva el registro', async () => {
    const g = await createGym('A');
    await archiveGym(g.id, true);
    expect(await listGyms()).toHaveLength(0);
    expect((await db.gyms.get(g.id))?.archivada).toBe(true);
  });

  it('soft-delete no borra las sesiones que lo referencian', async () => {
    const g = await createGym('A');
    await db.workoutSessions.put({
      id: 's1', userId: null, gymId: g.id, fecha: Date.now(),
      updatedAt: Date.now(), deletedAt: null,
    });
    await softDeleteGym(g.id);
    expect(await listGyms()).toHaveLength(0);
    expect((await db.gyms.get(g.id))?.deletedAt).not.toBeNull();
    expect((await db.workoutSessions.get('s1'))?.deletedAt).toBeNull();
    expect((await db.workoutSessions.get('s1'))?.gymId).toBe(g.id);
  });

  it('reordena por la lista de ids dada', async () => {
    const a = await createGym('A');
    const b = await createGym('B');
    await reorderGyms([b.id, a.id]);
    expect((await listGyms()).map((g) => g.id)).toEqual([b.id, a.id]);
  });

  it('getGymsMap incluye archivados para resolver nombres', async () => {
    const g = await createGym('A');
    await archiveGym(g.id, true);
    const map = await getGymsMap();
    expect(map.get(g.id)?.nombre).toBe('A');
  });
});
```

- [ ] **Step 2: Ejecutar (debe fallar)**

Run: `npm run test -- gyms.test.ts`
Expected: FAIL — el módulo no existe.

- [ ] **Step 3: Implementar el repositorio**

Crea `lib/repositories/gyms.ts`:

```ts
import { db } from '@/lib/db/database';
import type { Gym } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

/** Gimnasios activos (no borrados, no archivados), ordenados. */
export async function listGyms(): Promise<Gym[]> {
  const all = activo(await db.gyms.toArray()).filter((g) => !g.archivada);
  return all.sort((a, b) => a.orden - b.orden);
}

export async function createGym(nombre: string): Promise<Gym> {
  const existentes = activo(await db.gyms.toArray());
  const gym: Gym = {
    id: crypto.randomUUID(),
    userId: null,
    nombre: nombre.trim(),
    orden: existentes.length,
    archivada: false,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.gyms.put(gym);
  return gym;
}

export async function renameGym(id: string, nombre: string): Promise<void> {
  await db.gyms.update(id, { nombre: nombre.trim(), updatedAt: now() });
}

export async function archiveGym(id: string, archivada: boolean): Promise<void> {
  await db.gyms.update(id, { archivada, updatedAt: now() });
}

export async function softDeleteGym(id: string): Promise<void> {
  await db.gyms.update(id, { deletedAt: now(), updatedAt: now() });
}

export async function reorderGyms(idsEnOrden: string[]): Promise<void> {
  const ts = now();
  await db.transaction('rw', db.gyms, async () => {
    for (let i = 0; i < idsEnOrden.length; i++) {
      await db.gyms.update(idsEnOrden[i], { orden: i, updatedAt: ts });
    }
  });
}

/** Mapa id→Gym incluyendo archivados (no borrados) para resolver nombres en la UI. */
export async function getGymsMap(): Promise<Map<string, Gym>> {
  const map = new Map<string, Gym>();
  for (const g of activo(await db.gyms.toArray())) map.set(g.id, g);
  return map;
}

/** Nombre a mostrar para un gymId (o "Sin gimnasio"). */
export function gymDisplayName(gymId: string | null | undefined, map: Map<string, Gym>): string {
  if (!gymId) return 'Sin gimnasio';
  return map.get(gymId)?.nombre ?? 'Sin gimnasio';
}
```

- [ ] **Step 4: Ejecutar (debe pasar)**

Run: `npm run test -- gyms.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/gyms.ts lib/repositories/gyms.test.ts
git commit -m "feat(repo): repositorio de gimnasios (CRUD + soft-delete sin tocar sesiones)"
```

---

## Task 5: `startSession` con gymId, `getLastSet` por gimnasio y backfill

**Files:**
- Modify: `lib/repositories/workouts.ts`
- Test: `lib/repositories/workouts.test.ts`

- [ ] **Step 1: Escribir los tests (fallan)**

En `lib/repositories/workouts.test.ts`:

1. Añade `db.gyms.clear()` al `beforeEach`.
2. Importa las dos funciones nuevas en la línea de import de `@/lib/repositories/workouts`:
   `countSessionsWithoutGym, assignGymToSessionsWithoutGym`.
3. Añade este bloque al final:

```ts
describe('gimnasios', () => {
  it('startSession guarda el gymId', async () => {
    const s = await startSession({ gymId: 'g1' });
    expect((await getSession(s.id))?.gymId).toBe('g1');
  });

  it('getLastSet filtra por gimnasio', async () => {
    const a = await startSession({ gymId: 'gymA' });
    const leA = await addLoggedExercise(a.id, 'seed-sentadilla');
    await addSet(leA.id, { peso: 100, reps: 5 });
    await new Promise((res) => setTimeout(res, 3));
    const b = await startSession({ gymId: 'gymB' });
    const leB = await addLoggedExercise(b.id, 'seed-sentadilla');
    await addSet(leB.id, { peso: 80, reps: 5 });
    await new Promise((res) => setTimeout(res, 3));
    const nueva = await startSession({ gymId: 'gymA' });
    // Sin gimnasio: la más reciente (gymB, 80).
    expect(await getLastSet('seed-sentadilla', nueva.id)).toMatchObject({ peso: 80 });
    // Filtrando por gymA: la de gymA (100), no la de gymB.
    expect(await getLastSet('seed-sentadilla', nueva.id, 'gymA')).toMatchObject({ peso: 100 });
  });

  it('backfill asigna gymId a las sesiones sin gimnasio y cuenta', async () => {
    await startSession({}); // sin gym
    await startSession({}); // sin gym
    await startSession({ gymId: 'g1' });
    expect(await countSessionsWithoutGym()).toBe(2);
    const n = await assignGymToSessionsWithoutGym('g1');
    expect(n).toBe(2);
    expect(await countSessionsWithoutGym()).toBe(0);
    const todas = await listSessions();
    expect(todas.every((s) => s.gymId === 'g1')).toBe(true);
  });
});
```

- [ ] **Step 2: Ejecutar (debe fallar)**

Run: `npm run test -- workouts.test.ts`
Expected: FAIL — `gymId` no se guarda / funciones de backfill no existen.

- [ ] **Step 3: Implementar los cambios en workouts.ts**

En `lib/repositories/workouts.ts`:

1. Cambia la firma e implementación de `startSession`:

```ts
export async function startSession(input: { routineDayId?: string; gymId?: string | null }): Promise<WorkoutSession> {
  const ts = now();
  const session: WorkoutSession = {
    id: crypto.randomUUID(),
    userId: null,
    routineDayId: input.routineDayId,
    gymId: input.gymId ?? null,
    fecha: ts,
    updatedAt: ts,
    deletedAt: null,
  };
  await db.workoutSessions.put(session);
  if (input.routineDayId) {
    const dayExercises = await listDayExercises(input.routineDayId);
    for (const re of dayExercises) {
      await addLoggedExercise(session.id, re.exerciseId);
    }
  }
  return session;
}
```

2. Cambia la firma e implementación de `getLastSet` (añade `gymId` opcional):

```ts
export async function getLastSet(
  exerciseId: string,
  excludeSessionId?: string,
  gymId?: string | null,
): Promise<LoggedSet | undefined> {
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
  // Filtrar por gimnasio si se pide.
  const candidatos = gymId == null
    ? les
    : les.filter((le) => gymBySession.get(le.sessionId) === gymId);
  candidatos.sort((a, b) => (fechaBySession.get(b.sessionId) ?? 0) - (fechaBySession.get(a.sessionId) ?? 0));
  for (const le of candidatos) {
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    if (sets.length > 0) {
      sets.sort((a, b) => b.orden - a.orden);
      return sets[0];
    }
  }
  return undefined;
}
```

3. Añade al final del fichero las funciones de backfill:

```ts
/** Nº de sesiones activas sin gimnasio asignado. */
export async function countSessionsWithoutGym(): Promise<number> {
  return activo(await db.workoutSessions.toArray()).filter((s) => !s.gymId).length;
}

/** Asigna gymId a todas las sesiones activas sin gimnasio. Devuelve cuántas tocó. */
export async function assignGymToSessionsWithoutGym(gymId: string): Promise<number> {
  const ts = now();
  const sinGym = activo(await db.workoutSessions.toArray()).filter((s) => !s.gymId);
  await db.transaction('rw', db.workoutSessions, async () => {
    for (const s of sinGym) {
      await db.workoutSessions.update(s.id, { gymId, updatedAt: ts });
    }
  });
  return sinGym.length;
}
```

- [ ] **Step 4: Ejecutar (debe pasar)**

Run: `npm run test -- workouts.test.ts`
Expected: PASS (incluidos los tests previos de autorrelleno, que siguen llamando con 2 argumentos).

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/workouts.ts lib/repositories/workouts.test.ts
git commit -m "feat(repo): gymId en sesión, autorrelleno por gimnasio y backfill"
```

---

## Task 6: Filtro por gimnasio en las stats

**Files:**
- Modify: `lib/repositories/stats.ts`
- Test: `lib/repositories/stats.test.ts`

- [ ] **Step 1: Escribir los tests (fallan)**

En `lib/repositories/stats.test.ts` añade (importa lo que falte de `@/lib/repositories/stats` y `@/lib/repositories/workouts`):

```ts
describe('filtro por gimnasio', () => {
  it('getExercisePRs y getExerciseProgress filtran por gimnasio', async () => {
    const a = await startSession({ gymId: 'gymA' });
    const leA = await addLoggedExercise(a.id, 'seed-sentadilla');
    await addSet(leA.id, { peso: 100, reps: 5 });
    const b = await startSession({ gymId: 'gymB' });
    const leB = await addLoggedExercise(b.id, 'seed-sentadilla');
    await addSet(leB.id, { peso: 60, reps: 5 });

    expect((await getExercisePRs('seed-sentadilla'))?.maxPeso).toBe(100); // todos
    expect((await getExercisePRs('seed-sentadilla', 'gymB'))?.maxPeso).toBe(60); // solo B
    const progB = await getExerciseProgress('seed-sentadilla', 'gymB');
    expect(progB).toHaveLength(1);
    expect(progB[0].maxPeso).toBe(60);
  });

  it('listSessionSummaries y getVolumeByMuscle filtran por gimnasio', async () => {
    const a = await startSession({ gymId: 'gymA' });
    const leA = await addLoggedExercise(a.id, 'seed-sentadilla');
    await addSet(leA.id, { peso: 100, reps: 5 });
    await startSession({ gymId: 'gymB' });

    expect(await listSessionSummaries()).toHaveLength(2);          // todos
    expect(await listSessionSummaries('gymA')).toHaveLength(1);    // solo A
    const volA = await getVolumeByMuscle(0, 'gymA');
    expect(volA.reduce((acc, v) => acc + v.volumen, 0)).toBe(500); // 100*5
    expect(await getVolumeByMuscle(0, 'gymB')).toHaveLength(0);
  });
});
```

Asegúrate de que el `beforeEach` del fichero limpia `workoutSessions`, `loggedExercises`, `loggedSets` (si no lo hace ya, añádelo).

- [ ] **Step 2: Ejecutar (debe fallar)**

Run: `npm run test -- stats.test.ts`
Expected: FAIL — las funciones no aceptan `gymId`.

- [ ] **Step 3: Implementar el filtro en stats.ts**

En `lib/repositories/stats.ts`:

1. Cambia `setsDeEjercicio` para aceptar `gymId`:

```ts
async function setsDeEjercicio(exerciseId: string, gymId?: string | null): Promise<{ set: LoggedSet; fecha: number }[]> {
  const les = activo(await db.loggedExercises.where('exerciseId').equals(exerciseId).toArray());
  if (les.length === 0) return [];
  const sessionIds = [...new Set(les.map((le) => le.sessionId))];
  const sessions = await db.workoutSessions.bulkGet(sessionIds);
  const fechaBy = new Map<string, number>();
  for (const s of sessions) {
    if (!s || s.deletedAt !== null) continue;
    if (gymId != null && (s.gymId ?? null) !== gymId) continue; // filtro por gimnasio
    fechaBy.set(s.id, s.fecha);
  }
  const out: { set: LoggedSet; fecha: number }[] = [];
  for (const le of les) {
    const fecha = fechaBy.get(le.sessionId);
    if (fecha === undefined) continue;
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    for (const set of sets) out.push({ set, fecha });
  }
  return out;
}
```

2. Propaga `gymId` en las firmas:

```ts
export async function getExerciseProgress(exerciseId: string, gymId?: string | null): Promise<ExerciseProgressPoint[]> {
  const data = await setsDeEjercicio(exerciseId, gymId);
  // ... resto igual
```

```ts
export async function getExercisePRs(exerciseId: string, gymId?: string | null): Promise<ExercisePRs | null> {
  const data = await setsDeEjercicio(exerciseId, gymId);
  // ... resto igual
```

3. `getVolumeByMuscle` con filtro:

```ts
export async function getVolumeByMuscle(sinceTs = 0, gymId?: string | null): Promise<VolumeByMuscle[]> {
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => s.fecha >= sinceTs)
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId);
  // ... resto igual (sessionIds, les, etc.)
```

4. `listSessionSummaries` con filtro:

```ts
export async function listSessionSummaries(gymId?: string | null): Promise<SessionSummary[]> {
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId)
    .sort((a, b) => b.fecha - a.fecha);
  // ... resto igual
```

- [ ] **Step 4: Ejecutar (debe pasar)**

Run: `npm run test -- stats.test.ts`
Expected: PASS (los tests previos sin `gymId` siguen verdes).

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/stats.ts lib/repositories/stats.test.ts
git commit -m "feat(stats): filtro por gimnasio en progreso, PRs, volumen y resúmenes"
```

---

## Task 7: Backup incluye gimnasios

**Files:**
- Modify: `lib/repositories/backup.ts`
- Test: `lib/repositories/backup.test.ts`

- [ ] **Step 1: Escribir el test (falla)**

En `lib/repositories/backup.test.ts` añade (ajusta imports e `beforeEach` para limpiar `db.gyms`):

```ts
it('exporta e importa los gimnasios', async () => {
  await db.gyms.clear();
  await db.gyms.put({
    id: 'g1', userId: null, nombre: 'Gold\'s', orden: 0, archivada: false,
    updatedAt: Date.now(), deletedAt: null,
  });
  const backup = await exportData();
  expect(backup.data.gyms).toHaveLength(1);
  await db.gyms.clear();
  await importData(backup);
  expect((await db.gyms.get('g1'))?.nombre).toBe("Gold's");
});
```

- [ ] **Step 2: Ejecutar (debe fallar)**

Run: `npm run test -- backup.test.ts`
Expected: FAIL — `backup.data.gyms` no existe.

- [ ] **Step 3: Implementar en backup.ts**

En `lib/repositories/backup.ts`:

1. Importa `Gym` en el import de tipos.
2. Añade `gyms: Gym[];` a `BackupFile['data']`.
3. Cambia `version: 3` → `version: 4` en `exportData`.
4. Añade `gyms: await db.gyms.toArray(),` al objeto `data` de `exportData`.
5. En `importData`, añade `db.gyms` al array `tables` y, dentro de la transacción:
   `if (d.gyms?.length) await db.gyms.bulkPut(d.gyms);`

- [ ] **Step 4: Ejecutar (debe pasar)**

Run: `npm run test -- backup.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/backup.ts lib/repositories/backup.test.ts
git commit -m "feat(backup): incluir gimnasios en export/import (v4)"
```

---

## Task 8: Estado del filtro de vista (localStorage)

**Files:**
- Create: `lib/gym-filter.ts`
- Test: `lib/gym-filter.test.ts`

- [ ] **Step 1: Escribir el test (falla)**

Crea `lib/gym-filter.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { getGymFilter, setGymFilter } from '@/lib/gym-filter';

beforeEach(() => localStorage.clear());

describe('gymFilter', () => {
  it('por defecto es "all"', () => {
    expect(getGymFilter()).toBe('all');
  });
  it('persiste el valor elegido', () => {
    setGymFilter('g1');
    expect(getGymFilter()).toBe('g1');
    expect(localStorage.getItem('gymlog.gymFilter')).toBe('g1');
  });
});
```

- [ ] **Step 2: Ejecutar (debe fallar)**

Run: `npm run test -- gym-filter.test.ts`
Expected: FAIL — módulo inexistente.

- [ ] **Step 3: Implementar**

Crea `lib/gym-filter.ts`:

```ts
'use client';

import { useEffect, useState } from 'react';

const KEY = 'gymlog.gymFilter';
export type GymFilter = 'all' | string;

export function getGymFilter(): GymFilter {
  if (typeof localStorage === 'undefined') return 'all';
  return localStorage.getItem(KEY) ?? 'all';
}

export function setGymFilter(value: GymFilter): void {
  if (typeof localStorage === 'undefined') return;
  localStorage.setItem(KEY, value);
  window.dispatchEvent(new CustomEvent('gymfilterchange'));
}

/** Hook React: lee el filtro y se re-renderiza cuando cambia (misma pestaña). */
export function useGymFilter(): [GymFilter, (v: GymFilter) => void] {
  const [filtro, setFiltro] = useState<GymFilter>('all');
  useEffect(() => {
    setFiltro(getGymFilter());
    const onChange = () => setFiltro(getGymFilter());
    window.addEventListener('gymfilterchange', onChange);
    return () => window.removeEventListener('gymfilterchange', onChange);
  }, []);
  return [filtro, (v) => { setGymFilter(v); setFiltro(v); }];
}

/** Convierte el filtro en el argumento para las stats (undefined = todos). */
export function filtroAGymId(f: GymFilter): string | undefined {
  return f === 'all' ? undefined : f;
}
```

- [ ] **Step 4: Ejecutar (debe pasar)**

Run: `npm run test -- gym-filter.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add lib/gym-filter.ts lib/gym-filter.test.ts
git commit -m "feat: estado del filtro de gimnasio en localStorage + hook"
```

---

## Task 9: Selector de gimnasio al empezar entreno

**Files:**
- Create: `components/gym-picker.tsx`
- Modify: `components/start-workout.tsx`
- Test: `components/start-workout.test.tsx`

- [ ] **Step 1: Crear el componente selector**

Crea `components/gym-picker.tsx`:

```tsx
'use client';

import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { listGyms } from '@/lib/repositories/gyms';

export function GymPicker({ onPick }: { onPick: (gymId: string) => void }) {
  const gyms = useLiveQuery(() => listGyms(), []);
  if (gyms === undefined) return <p className="label-mono text-xs text-muted-foreground">Cargando…</p>;
  if (gyms.length === 0) {
    return (
      <div className="brutal-box p-4 text-center">
        <p className="label-mono mb-3 text-xs text-muted-foreground">Aún no tienes gimnasios</p>
        <Link
          href="/ajustes"
          className="label-mono inline-block border-2 border-foreground bg-primary px-3 py-2 text-[11px] text-primary-foreground brutal-shadow-sm"
        >
          Crea tu primer gimnasio
        </Link>
      </div>
    );
  }
  return (
    <div className="space-y-2">
      <p className="label-mono text-[11px] text-muted-foreground">¿En qué gimnasio entrenas hoy?</p>
      <div className="grid grid-cols-2 gap-2">
        {gyms.map((g) => (
          <button
            key={g.id}
            onClick={() => onPick(g.id)}
            className="brutal-box px-3 py-3 text-left font-[family-name:var(--font-display)] text-lg uppercase tracking-wide transition-transform active:translate-x-[2px] active:translate-y-[2px]"
          >
            {g.nombre}
          </button>
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Escribir el test actualizado (falla)**

Reemplaza el contenido de `components/start-workout.test.tsx` por:

```tsx
import { it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { createGym } from '@/lib/repositories/gyms';
import { StartWorkout } from '@/components/start-workout';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.routines.clear();
  await db.routineDays.clear();
  await db.gyms.clear();
  push.mockClear();
});

it('elige gimnasio, empieza un entreno libre y navega a la sesión', async () => {
  const g = await createGym("Gold's");
  render(<StartWorkout />);
  await userEvent.click(screen.getByRole('button', { name: 'Empezar entreno libre' }));
  await userEvent.click(await screen.findByRole('button', { name: "Gold's" }));
  await waitFor(() => expect(push).toHaveBeenCalled());
  expect(await db.workoutSessions.count()).toBe(1);
  const sesion = (await db.workoutSessions.toArray())[0];
  expect(sesion.gymId).toBe(g.id);
  expect(push).toHaveBeenCalledWith(`/entrenar/${sesion.id}`);
});
```

- [ ] **Step 3: Ejecutar (debe fallar)**

Run: `npm run test -- start-workout.test.tsx`
Expected: FAIL — no aparece el botón del gimnasio tras pulsar "Empezar".

- [ ] **Step 4: Implementar el flujo en start-workout.tsx**

Reemplaza `components/start-workout.tsx` por:

```tsx
'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { ArrowRight } from 'lucide-react';
import { useLiveQuery } from 'dexie-react-hooks';
import { listRoutines, listDays } from '@/lib/repositories/routines';
import { startSession } from '@/lib/repositories/workouts';
import type { Routine } from '@/lib/db/types';
import { Button } from '@/components/ui/button';
import { GymPicker } from '@/components/gym-picker';

type Pendiente = { tipo: 'libre' } | { tipo: 'dia'; routineDayId: string } | null;

export function StartWorkout() {
  const router = useRouter();
  const routines = useLiveQuery(() => listRoutines(), []);
  const [pendiente, setPendiente] = useState<Pendiente>(null);

  async function empezarConGym(gymId: string) {
    if (!pendiente) return;
    const s = pendiente.tipo === 'dia'
      ? await startSession({ routineDayId: pendiente.routineDayId, gymId })
      : await startSession({ gymId });
    router.push(`/entrenar/${s.id}`);
  }

  if (pendiente) {
    return (
      <div className="space-y-4">
        <GymPicker onPick={empezarConGym} />
        <button
          className="label-mono text-[11px] text-muted-foreground underline"
          onClick={() => setPendiente(null)}
        >
          Cancelar
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-7">
      <Button
        onClick={() => setPendiente({ tipo: 'libre' })}
        size="lg"
        className="w-full font-[family-name:var(--font-display)] text-xl tracking-wide"
      >
        Empezar entreno libre
        <ArrowRight className="size-5" strokeWidth={3} />
      </Button>

      {(routines ?? []).length > 0 && (
        <section className="space-y-3">
          <h2 className="label-mono text-[11px] text-muted-foreground">Desde una rutina</h2>
          {(routines ?? []).map((r) => (
            <RoutineDaysToStart
              key={r.id}
              routine={r}
              onStart={(routineDayId) => setPendiente({ tipo: 'dia', routineDayId })}
            />
          ))}
        </section>
      )}
    </div>
  );
}

function RoutineDaysToStart({ routine, onStart }: { routine: Routine; onStart: (dayId: string) => void }) {
  const dias = useLiveQuery(() => listDays(routine.id), [routine.id]);
  if ((dias ?? []).length === 0) return null;
  return (
    <div className="brutal-box">
      <p className="border-b-2 border-foreground bg-foreground px-3 py-2 font-[family-name:var(--font-display)] text-lg uppercase tracking-wide text-background">
        {routine.nombre}
      </p>
      <ul>
        {(dias ?? []).map((d) => (
          <li
            key={d.id}
            className="flex items-center justify-between gap-2 border-b-2 border-foreground px-3 py-2.5 last:border-b-0"
          >
            <span className="font-semibold">{d.nombre}</span>
            <button
              className="label-mono inline-flex items-center gap-1 border-2 border-foreground bg-primary px-2.5 py-1.5 text-[10px] text-primary-foreground transition-transform active:translate-x-[2px] active:translate-y-[2px]"
              onClick={() => onStart(d.id)}
            >
              Empezar <ArrowRight className="size-3" strokeWidth={3} />
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
```

- [ ] **Step 5: Ejecutar (debe pasar)**

Run: `npm run test -- start-workout.test.tsx`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add components/gym-picker.tsx components/start-workout.tsx components/start-workout.test.tsx
git commit -m "feat(ui): selector de gimnasio obligatorio al empezar un entreno"
```

---

## Task 10: Autorrelleno por gimnasio en la tarjeta de registro

**Files:**
- Modify: `app/entrenar/[sessionId]/page.tsx`
- Modify: `components/logged-exercise-card.tsx`

- [ ] **Step 1: Pasar el gymId de la sesión a la tarjeta**

En `app/entrenar/[sessionId]/page.tsx`, en el `.map` que renderiza las tarjetas, añade la prop `gymId`:

```tsx
        {(ejercicios ?? []).map((le) => (
          <LoggedExerciseCard key={le.id} loggedExercise={le} sessionId={sessionId} gymId={session.gymId ?? undefined} />
        ))}
```

(En ese punto `session` ya está cargada y no borrada por las guardas previas.)

- [ ] **Step 2: Aceptar y usar el gymId en la tarjeta**

En `components/logged-exercise-card.tsx`:

1. Amplía las props:

```tsx
export function LoggedExerciseCard({
  loggedExercise,
  sessionId,
  gymId,
}: {
  loggedExercise: LoggedExercise;
  sessionId: string;
  gymId?: string;
}) {
```

2. En `añadirSerie`, pasa `gymId` al autorrelleno (3er argumento):

```tsx
    const previa = await getLastSet(loggedExercise.exerciseId, sessionId, gymId);
```

- [ ] **Step 3: Verificar tipos y tests**

Run: `npx tsc --noEmit && npm run test -- logged-exercise-card.test.tsx`
Expected: sin errores de tipos; el test existente de la tarjeta sigue verde (no pasa `gymId`, autorrelleno global).

- [ ] **Step 4: Commit**

```bash
git add app/entrenar/[sessionId]/page.tsx components/logged-exercise-card.tsx
git commit -m "feat(ui): autorrelleno usa el gimnasio de la sesión"
```

---

## Task 11: Gestión de gimnasios + backfill en Ajustes

**Files:**
- Create: `components/gym-manager.tsx`
- Create: `components/gym-manager.test.tsx`
- Modify: `app/ajustes/page.tsx`

- [ ] **Step 1: Escribir el test (falla)**

Crea `components/gym-manager.test.tsx`:

```tsx
import { it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { GymManager } from '@/components/gym-manager';

beforeEach(async () => {
  await db.gyms.clear();
  await db.workoutSessions.clear();
});

it('crea un gimnasio y lo muestra en la lista', async () => {
  render(<GymManager />);
  await userEvent.type(screen.getByPlaceholderText('Nombre del gimnasio'), "Gold's");
  await userEvent.click(screen.getByRole('button', { name: 'Añadir' }));
  await waitFor(() => expect(screen.getByText("Gold's")).toBeInTheDocument());
  expect(await db.gyms.count()).toBe(1);
});
```

- [ ] **Step 2: Ejecutar (debe fallar)**

Run: `npm run test -- gym-manager.test.tsx`
Expected: FAIL — módulo inexistente.

- [ ] **Step 3: Implementar el gestor**

Crea `components/gym-manager.tsx`:

```tsx
'use client';

import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import {
  listGyms, createGym, renameGym, softDeleteGym,
} from '@/lib/repositories/gyms';
import { countSessionsWithoutGym, assignGymToSessionsWithoutGym } from '@/lib/repositories/workouts';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';

export function GymManager() {
  const gyms = useLiveQuery(() => listGyms(), []);
  const sinGym = useLiveQuery(() => countSessionsWithoutGym(), []);
  const [nombre, setNombre] = useState('');
  const [destino, setDestino] = useState('');

  async function añadir() {
    if (!nombre.trim()) return;
    await createGym(nombre);
    setNombre('');
  }

  return (
    <section className="space-y-3">
      <h2 className="label-mono text-[11px] text-muted-foreground">Gimnasios</h2>

      <div className="flex gap-2">
        <Input
          placeholder="Nombre del gimnasio"
          value={nombre}
          onChange={(e) => setNombre(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') void añadir(); }}
        />
        <Button onClick={añadir}>Añadir</Button>
      </div>

      <ul className="brutal-box divide-y-2 divide-foreground">
        {(gyms ?? []).length === 0 && (
          <li className="label-mono px-3 py-3 text-xs text-muted-foreground">Sin gimnasios todavía.</li>
        )}
        {(gyms ?? []).map((g) => (
          <li key={g.id} className="flex items-center justify-between gap-2 px-3 py-2.5">
            <span className="font-semibold">{g.nombre}</span>
            <span className="flex gap-2">
              <button
                className="label-mono text-[10px] text-muted-foreground hover:text-foreground"
                onClick={async () => {
                  const nuevo = window.prompt('Nuevo nombre', g.nombre);
                  if (nuevo?.trim()) await renameGym(g.id, nuevo);
                }}
              >
                Renombrar
              </button>
              <button
                className="label-mono text-[10px] text-muted-foreground hover:text-destructive"
                onClick={async () => {
                  if (window.confirm(`¿Borrar "${g.nombre}"? Sus entrenos se conservan.`)) {
                    await softDeleteGym(g.id);
                  }
                }}
              >
                Borrar
              </button>
            </span>
          </li>
        ))}
      </ul>

      {(sinGym ?? 0) > 0 && (gyms ?? []).length > 0 && (
        <div className="brutal-box space-y-2 p-3">
          <p className="label-mono text-[11px] text-muted-foreground">
            Tienes {sinGym} entreno{sinGym === 1 ? '' : 's'} sin gimnasio. Asígnalos a:
          </p>
          <div className="flex gap-2">
            <select
              className="h-11 flex-1 border-2 border-input bg-card px-2 text-base font-medium"
              value={destino}
              onChange={(e) => setDestino(e.target.value)}
            >
              <option value="">Elige…</option>
              {(gyms ?? []).map((g) => (
                <option key={g.id} value={g.id}>{g.nombre}</option>
              ))}
            </select>
            <Button
              disabled={!destino}
              onClick={async () => { if (destino) await assignGymToSessionsWithoutGym(destino); }}
            >
              Asignar
            </Button>
          </div>
        </div>
      )}
    </section>
  );
}
```

- [ ] **Step 4: Ejecutar (debe pasar)**

Run: `npm run test -- gym-manager.test.tsx`
Expected: PASS

- [ ] **Step 5: Montar el gestor en Ajustes**

En `app/ajustes/page.tsx`:

1. Importa: `import { GymManager } from '@/components/gym-manager';`
2. Añade `<GymManager />` dentro del `div` raíz, antes de la `<section>` de copia de seguridad.

- [ ] **Step 6: Verificar**

Run: `npx tsc --noEmit && npm run test -- gym-manager.test.tsx`
Expected: sin errores.

- [ ] **Step 7: Commit**

```bash
git add components/gym-manager.tsx components/gym-manager.test.tsx app/ajustes/page.tsx
git commit -m "feat(ui): gestión de gimnasios y backfill en Ajustes"
```

---

## Task 12: Filtro de gimnasio en Progreso e Historial

**Files:**
- Create: `components/gym-filter.tsx`
- Modify: `app/progreso/page.tsx`, `components/exercise-progress.tsx`
- Modify: `app/historial/page.tsx`, `components/session-summary-list.tsx`

- [ ] **Step 1: Crear el componente de chips de filtro**

Crea `components/gym-filter.tsx`:

```tsx
'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { listGyms } from '@/lib/repositories/gyms';
import { useGymFilter } from '@/lib/gym-filter';

export function GymFilter() {
  const gyms = useLiveQuery(() => listGyms(), []);
  const [filtro, setFiltro] = useGymFilter();
  if ((gyms ?? []).length === 0) return null; // sin gimnasios, sin filtro

  const opciones = [{ id: 'all', nombre: 'Todos' }, ...(gyms ?? [])];
  return (
    <div className="flex flex-wrap gap-2">
      {opciones.map((o) => {
        const activo = filtro === o.id;
        return (
          <button
            key={o.id}
            onClick={() => setFiltro(o.id)}
            className={`label-mono border-2 border-foreground px-2.5 py-1.5 text-[10px] transition-transform active:translate-x-[1px] active:translate-y-[1px] ${
              activo ? 'bg-primary text-primary-foreground brutal-shadow-sm' : 'bg-card text-muted-foreground'
            }`}
          >
            {o.nombre}
          </button>
        );
      })}
    </div>
  );
}
```

- [ ] **Step 2: Progreso — usar el filtro**

En `app/progreso/page.tsx`:

1. Imports nuevos:

```tsx
import { GymFilter } from '@/components/gym-filter';
import { useGymFilter, filtroAGymId } from '@/lib/gym-filter';
```

2. En el componente, lee el filtro y pásalo a `getVolumeByMuscle` y a `ExerciseProgress`:

```tsx
  const [filtro] = useGymFilter();
  const gymId = filtroAGymId(filtro);
  const volumen = useLiveQuery(() => getVolumeByMuscle(0, gymId), [gymId]);
```

3. Renderiza `<GymFilter />` justo bajo el `<h1>`:

```tsx
      <h1 className="text-2xl font-bold">Progreso</h1>
      <GymFilter />
```

4. Pasa `gymId` a `ExerciseProgress`:

```tsx
        {seleccion && <ExerciseProgress exerciseId={seleccion} gymId={gymId} />}
```

- [ ] **Step 3: ExerciseProgress — aceptar gymId**

En `components/exercise-progress.tsx`:

```tsx
export function ExerciseProgress({ exerciseId, gymId }: { exerciseId: string; gymId?: string }) {
  const progreso = useLiveQuery(() => getExerciseProgress(exerciseId, gymId), [exerciseId, gymId]);
  const prs = useLiveQuery(() => getExercisePRs(exerciseId, gymId), [exerciseId, gymId]);
  // ... resto igual
```

- [ ] **Step 4: Historial — usar el filtro + nombre del gimnasio**

En `app/historial/page.tsx`, añade `<GymFilter />` bajo la cabecera:

```tsx
import { GymFilter } from '@/components/gym-filter';
// ...
      <GymFilter />
      <SessionSummaryList />
```

En `components/session-summary-list.tsx`, filtra por gimnasio y muestra su nombre:

```tsx
'use client';

import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { listSessionSummaries } from '@/lib/repositories/stats';
import { getGymsMap, gymDisplayName } from '@/lib/repositories/gyms';
import { useGymFilter, filtroAGymId } from '@/lib/gym-filter';

export function SessionSummaryList() {
  const [filtro] = useGymFilter();
  const gymId = filtroAGymId(filtro);
  const resumenes = useLiveQuery(() => listSessionSummaries(gymId), [gymId]);
  const gymsMap = useLiveQuery(() => getGymsMap(), []);

  if (resumenes === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (resumenes.length === 0) return <p className="text-muted-foreground">Aún no has registrado entrenos.</p>;

  return (
    <ul className="divide-y-2 divide-foreground brutal-box">
      {resumenes.map(({ session, numEjercicios, volumen }) => (
        <li key={session.id}>
          <Link href={`/historial/${session.id}`} className="flex items-center justify-between p-3">
            <div>
              <span className="font-medium">{new Date(session.fecha).toLocaleDateString('es-ES')}</span>
              <span className="block text-xs text-muted-foreground">
                {numEjercicios} ejercicios · {gymDisplayName(session.gymId, gymsMap ?? new Map())}
              </span>
            </div>
            <span className="label-mono text-xs text-muted-foreground">{volumen} kg·rep</span>
          </Link>
        </li>
      ))}
    </ul>
  );
}
```

- [ ] **Step 5: Verificar tipos y suite**

Run: `npx tsc --noEmit && npm run test`
Expected: sin errores; 0 tests rotos (los tests existentes de estas vistas, si los hay, siguen verdes; `useGymFilter` por defecto es 'all' → sin filtro).

- [ ] **Step 6: Commit**

```bash
git add components/gym-filter.tsx app/progreso/page.tsx components/exercise-progress.tsx app/historial/page.tsx components/session-summary-list.tsx
git commit -m "feat(ui): filtro global de gimnasio en Progreso e Historial"
```

---

## Task 13: Mostrar y reasignar el gimnasio en el detalle de sesión

**Files:**
- Modify: `app/historial/[sessionId]/page.tsx`

- [ ] **Step 1: Mostrar el gimnasio y permitir cambiarlo**

En `app/historial/[sessionId]/page.tsx`:

1. Imports:

```tsx
import { listGyms, getGymsMap, gymDisplayName } from '@/lib/repositories/gyms';
```

2. Dentro de `SessionDetailPage`, tras obtener `session`:

```tsx
  const gyms = useLiveQuery(() => listGyms(), []);
  const gymsMap = useLiveQuery(() => getGymsMap(), []);
```

3. Bajo el `<h1>` de la fecha, añade un selector de gimnasio (cuando ya hay sesión cargada):

```tsx
      <div className="flex items-center gap-2">
        <span className="label-mono text-[11px] text-muted-foreground">Gimnasio:</span>
        <select
          className="h-9 border-2 border-input bg-card px-2 text-sm font-medium"
          value={session.gymId ?? ''}
          onChange={async (e) => {
            const v = e.target.value || null;
            await db.workoutSessions.update(session.id, { gymId: v, updatedAt: Date.now() });
          }}
        >
          <option value="">Sin gimnasio</option>
          {(gyms ?? []).map((g) => (
            <option key={g.id} value={g.id}>{g.nombre}</option>
          ))}
        </select>
      </div>
```

(`db` ya está importado en este fichero. `gymsMap`/`gymDisplayName` quedan disponibles por si se quiere mostrar el nombre fuera del select; no es obligatorio usarlos.)

- [ ] **Step 2: Verificar tipos**

Run: `npx tsc --noEmit`
Expected: sin errores. Si `gymsMap`/`gymDisplayName` quedan sin usar y el lint se queja, elimínalos del import.

- [ ] **Step 3: Commit**

```bash
git add app/historial/[sessionId]/page.tsx
git commit -m "feat(ui): reasignar el gimnasio de un entreno desde su detalle"
```

---

## Task 14: Verificación final

**Files:** ninguno (solo verificación).

- [ ] **Step 1: Suite completa**

Run: `npm run test`
Expected: todos los tests verdes (los 71 previos + los nuevos).

- [ ] **Step 2: Tipos**

Run: `npx tsc --noEmit`
Expected: sin errores.

- [ ] **Step 3: Lint**

Run: `npm run lint`
Expected: sin errores.

- [ ] **Step 4: Build de producción**

Run: `npm run build`
Expected: `Compiled successfully`, service worker de Serwist generado, proxy de Clerk presente.

- [ ] **Step 5: Commit final (si quedara algo sin commitear)**

```bash
git add -A
git commit -m "chore: verificación final multi-gimnasio (tests, tipos, lint, build)" || echo "nada que commitear"
```

---

## Notas de cierre

- **Reordenar gimnasios (UI) diferido conscientemente:** el repo incluye y testea `reorderGyms`,
  pero el gestor de Ajustes no expone reordenar (un usuario personal tiene 2-4 gimnasios; la
  lista por orden de creación basta). Si más adelante hace falta, los botones ▲▼ que llaman a
  `reorderGyms(nuevoOrden)` son triviales de añadir. Esto es la única desviación respecto al spec.
- Tras todo el plan: el push a Neon (`db:push`) ya está hecho en la Task 2; al desplegar, los endpoints `/api/sync/push|pull` sincronizarán `gyms` y `gym_id` automáticamente (ya registrados en `server-tables.ts`).
- Estética Brutalist Iron mantenida en los componentes nuevos (`brutal-box`, `label-mono`, borde 2px, sombra dura, chips cuadrados).
- Desplegar con `vercel --prod` cuando el usuario lo pida.
