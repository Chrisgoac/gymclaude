# Logros F1 — Fundación (entidad Achievement) (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entidad `Achievement` sincronizada (Dexie + repo + Drizzle + sync + backup + DDL Neon). Sin lógica de logros ni UI.

**Architecture:** Mismo patrón de entidad sincronizada de D1a/D2a/E1 (BodyMetric/ProgressPhoto/Mesocycle). Registra el desbloqueo de un hito (`clave` + `fechaDesbloqueo`), `unlockAchievement` idempotente por clave.

**Tech Stack:** Dexie v16 · Drizzle/Neon · vitest (proyecto `app` jsdom).

**Nota:** Fase **F1** del spec `docs/superpowers/specs/2026-06-10-logros-rachas-design.md`. Estado confirmado: Dexie en v15, backup en versión 11. La lógica (`lib/logros.ts`, stats, reconciliar) es F2; la UI es F3.

---

## File Structure

- **Modify** `lib/db/types.ts` — `Achievement`.
- **Modify** `lib/db/database.ts` — tabla `achievements` + `this.version(16)`.
- **Create** `lib/repositories/achievements.ts` — `listAchievements`/`unlockAchievement`/`getAchievementMap`.
- **Create** `lib/repositories/achievements.test.ts`.
- **Modify** `db/schema.ts` — tabla `achievements`.
- **Modify** `lib/sync/server-tables.ts`, `lib/sync/collect.ts`, `lib/sync/apply.ts` (+ tests).
- **Modify** `lib/repositories/backup.ts` (+ test) — versión 12.
- **Create** `scripts/migrate-achievements.mjs` — DDL.

---

## Task 1: Entidad `Achievement` + Dexie v16 + repo

**Files:**
- Modify: `lib/db/types.ts`
- Modify: `lib/db/database.ts`
- Create: `lib/repositories/achievements.ts`
- Create: `lib/repositories/achievements.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/db/types.ts` (`SyncMeta`, entidades recientes como `Mesocycle`), `lib/db/database.ts` (clase, `this.version(15)` con `mesocycles`), `lib/repositories/progress-photos.ts` (idioms `now`/`activo`, `unlockAchievement` se parece a un upsert idempotente).

- [ ] **Step 1: Add the entity** — in `lib/db/types.ts`:

```ts
export interface Achievement extends SyncMeta {
  userId: string | null;
  clave: string;
  fechaDesbloqueo: number;
}
```

- [ ] **Step 2: Dexie table** — in `lib/db/database.ts`:
1. Class field: `achievements!: Table<Achievement, string>;`
2. Add `Achievement` to the `import type { … } from './types';`.
3. After `this.version(15)...`:
```ts
    this.version(16).stores({
      achievements: 'id, clave, deletedAt',
    });
```

- [ ] **Step 3: Write the failing test** — create `lib/repositories/achievements.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { listAchievements, unlockAchievement, getAchievementMap } from '@/lib/repositories/achievements';

beforeEach(async () => { await db.achievements.clear(); });

describe('achievements repo', () => {
  it('unlockAchievement crea un logro con fecha', async () => {
    await unlockAchievement('sesiones-10');
    const todos = await listAchievements();
    expect(todos).toHaveLength(1);
    expect(todos[0].clave).toBe('sesiones-10');
    expect(todos[0].fechaDesbloqueo).toBeGreaterThan(0);
    expect(todos[0].deletedAt).toBeNull();
  });
  it('unlockAchievement es idempotente por clave', async () => {
    await unlockAchievement('racha-4');
    await unlockAchievement('racha-4');
    expect(await listAchievements()).toHaveLength(1);
  });
  it('getAchievementMap indexa por clave', async () => {
    await unlockAchievement('volumen-100k');
    const map = await getAchievementMap();
    expect(map.get('volumen-100k')?.clave).toBe('volumen-100k');
    expect(map.has('inexistente')).toBe(false);
  });
});
```

- [ ] **Step 4: Run → FAIL** (`npx vitest run lib/repositories/achievements.test.ts`).

- [ ] **Step 5: Implement** — create `lib/repositories/achievements.ts`:

```ts
import { db } from '@/lib/db/database';
import type { Achievement } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

/** Logros desbloqueados (activos). */
export async function listAchievements(): Promise<Achievement[]> {
  return activo(await db.achievements.toArray());
}

/** Desbloquea un hito por su clave. Idempotente: si ya está activo, no hace nada. */
export async function unlockAchievement(clave: string): Promise<void> {
  const existentes = activo(await db.achievements.where('clave').equals(clave).toArray());
  if (existentes.length > 0) return;
  const ts = now();
  const a: Achievement = {
    id: crypto.randomUUID(),
    userId: null,
    clave,
    fechaDesbloqueo: ts,
    updatedAt: ts,
    deletedAt: null,
  };
  await db.achievements.put(a);
}

/** Mapa clave → Achievement (activos). */
export async function getAchievementMap(): Promise<Map<string, Achievement>> {
  const todos = await listAchievements();
  return new Map(todos.map((a) => [a.clave, a]));
}
```

- [ ] **Step 6: Run → PASS** + `npx tsc --noEmit`.

- [ ] **Step 7: Commit**

```bash
git add lib/db/types.ts lib/db/database.ts lib/repositories/achievements.ts lib/repositories/achievements.test.ts
git commit -m "feat(logros): entidad Achievement + Dexie v16 + repo"
```

---

## Task 2: Tabla servidor `achievements` + SERVER_TABLES

**Files:**
- Modify: `db/schema.ts`
- Modify: `lib/sync/server-tables.ts`

- [ ] **Step 0: READ FIRST**

Read `db/schema.ts` (helper `sync`, tablas recientes `mesocycles`/`progressPhotos`), `lib/sync/server-tables.ts`.

- [ ] **Step 1: Add the table** — in `db/schema.ts`, after `mesocycles`:
```ts
export const achievements = pgTable('achievements', {
  ...sync,
  clave: text('clave').notNull(),
  fechaDesbloqueo: bigint('fecha_desbloqueo', { mode: 'number' }).notNull(),
});
```
(`text`/`bigint` ya están importados.)

- [ ] **Step 2: Register** — in `lib/sync/server-tables.ts`: `achievements: schema.achievements,`

- [ ] **Step 3: Typecheck** — `npx tsc --noEmit` clean.

- [ ] **Step 4: Commit**

```bash
git add db/schema.ts lib/sync/server-tables.ts
git commit -m "feat(logros): tabla achievements (servidor) + SERVER_TABLES"
```

---

## Task 3: collect/apply `achievements`

**Files:**
- Modify: `lib/sync/collect.ts`, `lib/sync/apply.ts`
- Modify: `lib/sync/apply.test.ts`, `lib/sync/collect.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/sync/collect.ts`/`apply.ts` (idiom `asSync`, entrada `mesocycles`) y los dos tests (idiom con `as unknown as SyncMeta[]`).

- [ ] **Step 1: Write failing tests.**

Append to `lib/sync/apply.test.ts`:
```ts
it('achievements se aplican por id (LWW)', async () => {
  await db.achievements.clear();
  await applyIncoming([{ table: 'achievements', records: [
    { id: 'a1', userId: 'u1', clave: 'sesiones-10', fechaDesbloqueo: 5, updatedAt: 100, deletedAt: null },
  ] as unknown as import('@/lib/db/types').SyncMeta[] }]);
  expect((await db.achievements.get('a1'))?.clave).toBe('sesiones-10');
});
```
Append to `lib/sync/collect.test.ts`:
```ts
it('sincroniza achievements', async () => {
  await db.achievements.clear();
  await db.achievements.put({ id: 'a-c1', userId: null, clave: 'racha-4', fechaDesbloqueo: 1, updatedAt: 1000, deletedAt: null });
  const changes = await collectDirty(0);
  expect(changes.find((c) => c.table === 'achievements')?.records).toHaveLength(1);
});
```

- [ ] **Step 2: Run → FAIL** (`npx vitest run lib/sync/`).

- [ ] **Step 3:** `apply.ts` → `achievements: asSync(db.achievements),` en `TABLE_BY_NAME`.
- [ ] **Step 4:** `collect.ts` → `{ name: 'achievements', table: asSync(db.achievements) },` en `SYNCABLE_TABLES` (sin `shouldSync`).

- [ ] **Step 5: Run → PASS** + `npx tsc --noEmit`.

- [ ] **Step 6: Commit**

```bash
git add lib/sync/collect.ts lib/sync/apply.ts lib/sync/apply.test.ts lib/sync/collect.test.ts
git commit -m "feat(sync): achievements en collect/apply"
```

---

## Task 4: Backup `achievements` (versión 12)

**Files:**
- Modify: `lib/repositories/backup.ts`, `lib/repositories/backup.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/backup.ts` (versión 11; `mesocycles` añadido en todos los puntos).

- [ ] **Step 1: Write the failing test** — append to `lib/repositories/backup.test.ts`:

```ts
it('exporta e importa achievements', async () => {
  await db.achievements.clear();
  await db.achievements.put({ id: 'ab1', userId: null, clave: 'mesociclo-1', fechaDesbloqueo: 9, updatedAt: 9, deletedAt: null });
  const backup = await exportData();
  expect(backup.data.achievements).toHaveLength(1);
  await db.achievements.clear();
  await importData(backup);
  expect((await db.achievements.get('ab1'))?.clave).toBe('mesociclo-1');
});
```

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement** — in `lib/repositories/backup.ts`:
1. Add `Achievement` to the `import type { … }`.
2. `BackupFile.data`: add `achievements: Achievement[];`
3. Bump `version: 11` → `version: 12`.
4. export: `achievements: await db.achievements.toArray(),`
5. import: add `db.achievements` to the tables tuple + `if (d.achievements?.length) await db.achievements.bulkPut(d.achievements);`
6. Fix any hand-rolled `BackupFile` fixture (`achievements: []`).

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/backup.ts lib/repositories/backup.test.ts
git commit -m "feat(backup): incluir achievements (version 12)"
```

---

## Task 5: DDL Neon `migrate-achievements.mjs`

**Files:**
- Create: `scripts/migrate-achievements.mjs`

- [ ] **Step 0: READ FIRST**

Read `scripts/migrate-progress-photos.mjs` (loader env, conexión, `CREATE TABLE IF NOT EXISTS`, verificación de columnas).

- [ ] **Step 1: Create `scripts/migrate-achievements.mjs`** — mirror, con:
```sql
CREATE TABLE IF NOT EXISTS achievements (
  id text PRIMARY KEY,
  user_id text NOT NULL,
  updated_at bigint NOT NULL,
  deleted_at bigint,
  server_updated_at bigint NOT NULL,
  clave text NOT NULL,
  fecha_desbloqueo bigint NOT NULL
);
```
Misma carga de `.env.local`, misma `DATABASE_URL_UNPOOLED || DATABASE_URL`, misma verificación post-create.

- [ ] **Step 2: Syntax-check only** — `node --check scripts/migrate-achievements.mjs` (NO ejecutar contra Neon).

- [ ] **Step 3: Commit**

```bash
git add scripts/migrate-achievements.mjs
git commit -m "chore(db): script DDL Neon para tabla achievements"
```

---

## Task 6: Verificación final

- [ ] **Step 1:** `npx vitest run && npx tsc --noEmit && npm run lint && npm run build`
Expected: todo verde.

---

## Self-Review (hecho)

- **Spec cobertura (F1):** entidad `Achievement` (clave + fechaDesbloqueo) ✓; Dexie v16 ✓; repo `listAchievements`/`unlockAchievement` (idempotente por clave)/`getAchievementMap` ✓; Drizzle `achievements` ✓; sync 3 registros sin shouldSync ✓; backup v12 ✓; DDL idempotente ✓.
- **Tipos consistentes:** `Achievement` reutilizado; repo `now`/`activo` como en el resto; `unlockAchievement` consulta por índice `clave`.
- **Sin placeholders:** todo el código presente. Catálogo/lógica (F2) y UI (F3) aparte.
