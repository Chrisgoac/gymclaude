# Coach B1 — Entidad CoachMessage sincronizada (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Una entidad `CoachMessage` que persiste y sincroniza el hilo de chat del coach entre dispositivos, sobre la que B4 montará la UI de chat.

**Architecture:** Entidad sync estándar (UUID id, columnas sync comunes) — patrón idéntico a `gyms`/`exercisePhotos`. Tabla Dexie + repo + tabla Drizzle `coach_messages` + registro en los 3 registros de sync + backup + migración DDL en Neon. Un único hilo (sin threadId), ordenado por `createdAt`.

**Tech Stack:** Next.js 16 + TypeScript · Dexie (IndexedDB) · Drizzle + Neon Postgres · Vitest + fake-indexeddb.

**Nota:** Fase **B1** del spec `docs/superpowers/specs/2026-06-09-coach-ia-design.md`. C0 ya añadió `userSettings` (Dexie v11, backup v7) — esto sigue su patrón pero con una tabla NORMAL (id UUID, sin clave compuesta), así que el push del servidor (`target: table.id`) funciona sin cambios. B2–B5 vienen en planes aparte.

---

## File Structure

- **Modify** `lib/db/types.ts` — interfaz `CoachMessage`.
- **Modify** `lib/db/database.ts` — tabla Dexie `coachMessages` (v12).
- **Create** `lib/repositories/coach.ts` — `listMessages`/`addMessage`/`clearThread`.
- **Create** `lib/repositories/coach.test.ts`.
- **Modify** `db/schema.ts` — tabla `coachMessages`.
- **Modify** `lib/sync/server-tables.ts` — registrar `coachMessages`.
- **Modify** `lib/sync/collect.ts` + `lib/sync/apply.ts` (+ tests) — registrar `coachMessages`.
- **Modify** `lib/repositories/backup.ts` (+ test) — incluir `coachMessages` (version 8).
- **Create** `scripts/migrate-coach-messages.mjs` — DDL Neon (se ejecuta al desplegar).

---

## Task 1: Entidad + tabla Dexie + repo

**Files:**
- Modify: `lib/db/types.ts`
- Modify: `lib/db/database.ts`
- Create: `lib/repositories/coach.ts`
- Create: `lib/repositories/coach.test.ts`

- [ ] **Step 1: Write the failing test** — `lib/repositories/coach.test.ts`

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import 'fake-indexeddb/auto';
import { db } from '@/lib/db/database';
import { listMessages, addMessage, clearThread } from '@/lib/repositories/coach';

beforeEach(async () => {
  await db.coachMessages.clear();
});

describe('coach repo', () => {
  it('addMessage + listMessages devuelve el hilo en orden cronológico', async () => {
    await addMessage('user', 'hola');
    await addMessage('assistant', 'qué tal');
    const hilo = await listMessages();
    expect(hilo.map((m) => [m.rol, m.contenido])).toEqual([
      ['user', 'hola'],
      ['assistant', 'qué tal'],
    ]);
    expect(hilo[0].createdAt).toBeLessThanOrEqual(hilo[1].createdAt);
  });

  it('addMessage genera id y marca sync', async () => {
    const m = await addMessage('user', 'x');
    expect(m.id).toBeTruthy();
    expect(m.userId).toBeNull();
    expect(m.deletedAt).toBeNull();
    expect(m.updatedAt).toBeGreaterThan(0);
  });

  it('clearThread hace tombstone de todos (listMessages vacío, filas siguen)', async () => {
    await addMessage('user', 'a');
    await addMessage('assistant', 'b');
    await clearThread();
    expect(await listMessages()).toHaveLength(0);
    expect(await db.coachMessages.count()).toBe(2); // tombstones, no borrado físico
  });
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/repositories/coach.test.ts`
Expected: FAIL — módulo/tabla inexistente.

- [ ] **Step 3: Add the type** — in `lib/db/types.ts`, near the other entities:

```ts
export interface CoachMessage extends SyncMeta {
  userId: string | null;
  rol: 'user' | 'assistant';
  contenido: string;
  /** epoch ms; ordena el hilo. */
  createdAt: number;
}
```

- [ ] **Step 4: Add the Dexie table** — in `lib/db/database.ts`:

1. Add `CoachMessage` to the existing `import type { … } from './types';`.
2. Declare the table field with the others: `coachMessages!: Table<CoachMessage, string>;`
3. After the `version(11)` block (the userSettings one), add:

```ts
    // v12: hilo del coach IA, sincronizado.
    this.version(12).stores({
      coachMessages: 'id, createdAt, deletedAt',
    });
```

- [ ] **Step 5: Implement the repo** — `lib/repositories/coach.ts`

```ts
import { db } from '@/lib/db/database';
import type { CoachMessage } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

/** Mensajes activos del hilo, en orden cronológico. */
export async function listMessages(): Promise<CoachMessage[]> {
  const all = activo(await db.coachMessages.toArray());
  return all.sort((a, b) => a.createdAt - b.createdAt);
}

/** Añade un mensaje (rol user|assistant) al hilo. */
export async function addMessage(rol: CoachMessage['rol'], contenido: string): Promise<CoachMessage> {
  const ts = now();
  const msg: CoachMessage = {
    id: crypto.randomUUID(),
    userId: null,
    rol,
    contenido,
    createdAt: ts,
    updatedAt: ts,
    deletedAt: null,
  };
  await db.coachMessages.put(msg);
  return msg;
}

/** Borra (tombstone) todos los mensajes del hilo. */
export async function clearThread(): Promise<void> {
  const ts = now();
  const all = activo(await db.coachMessages.toArray());
  for (const m of all) await db.coachMessages.update(m.id, { deletedAt: ts, updatedAt: ts });
}
```

- [ ] **Step 6: Run, verify pass**

Run: `npx vitest run lib/repositories/coach.test.ts`
Expected: PASS (3). Also `npx tsc --noEmit` clean.

- [ ] **Step 7: Commit**

```bash
git add lib/db/types.ts lib/db/database.ts lib/repositories/coach.ts lib/repositories/coach.test.ts
git commit -m "feat(coach): entidad CoachMessage + tabla Dexie v12 + repo del hilo"
```

---

## Task 2: Servidor — tabla `coach_messages` + registro

**Files:**
- Modify: `db/schema.ts`
- Modify: `lib/sync/server-tables.ts`

- [ ] **Step 0: READ FIRST**

Read `db/schema.ts` (the shared `sync` columns object — `id` PK, `userId`, `updatedAt`, `deletedAt`, `serverUpdatedAt` — and how `gyms`/`exercisePhotos` use `...sync`). Read `lib/sync/server-tables.ts`.

- [ ] **Step 1: Add the Drizzle table** — in `db/schema.ts`, at the end (uses the standard `...sync` spread — id is a normal single-column PK):

```ts
export const coachMessages = pgTable('coach_messages', {
  ...sync,
  rol: text('rol').notNull(),
  contenido: text('contenido').notNull(),
  createdAt: bigint('created_at', { mode: 'number' }).notNull(),
});
```

- [ ] **Step 2: Register it** — in `lib/sync/server-tables.ts`, add to `SERVER_TABLES`:

```ts
  coachMessages: schema.coachMessages,
```

- [ ] **Step 3: Typecheck + lint + suite**

Run: `npx tsc --noEmit && npm run lint && npx vitest run`
Expected: clean/green. (No live-DB test; sync tests use the in-memory transport. The push route is generic `target: table.id` — works for this standard table with no changes.)

- [ ] **Step 4: Commit**

```bash
git add db/schema.ts lib/sync/server-tables.ts
git commit -m "feat(sync): tabla coach_messages (servidor) + registro en SERVER_TABLES"
```

---

## Task 3: Cliente sync — registrar `coachMessages` en collect + apply

**Files:**
- Modify: `lib/sync/collect.ts`
- Modify: `lib/sync/apply.ts`
- Modify: `lib/sync/apply.test.ts`
- Modify: `lib/sync/collect.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/sync/collect.ts` (`SYNCABLE_TABLES`), `lib/sync/apply.ts` (`TABLE_BY_NAME`), and the existing `userSettings` entries in both (added in C0) to mirror the style. Read `lib/sync/apply.test.ts` + `lib/sync/collect.test.ts` for the test idioms (incl. the C0 userSettings convergence/collect tests).

- [ ] **Step 1: Write the failing tests**

Append to `lib/sync/apply.test.ts`:

```ts
it('coachMessages se aplican por id (LWW)', async () => {
  await db.coachMessages.clear();
  await applyIncoming([{ table: 'coachMessages', records: [
    { id: 'm1', userId: 'u1', rol: 'user', contenido: 'hola', createdAt: 100, updatedAt: 100, deletedAt: null },
  ] as unknown as import('@/lib/db/types').SyncMeta[] }]);
  const m = await db.coachMessages.get('m1');
  expect(m?.contenido).toBe('hola');
  expect(await db.coachMessages.count()).toBe(1);
});
```

Append to `lib/sync/collect.test.ts` (mirror the userSettings collect test; ensure `db.coachMessages.clear()` runs in its beforeEach or inline):

```ts
it('sincroniza coachMessages', async () => {
  await db.coachMessages.clear();
  await db.coachMessages.put({ id: 'cm1', userId: null, rol: 'user', contenido: 'q', createdAt: 1, updatedAt: 1000, deletedAt: null });
  const changes = await collectDirty(0);
  expect(changes.find((c) => c.table === 'coachMessages')?.records).toHaveLength(1);
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/sync/apply.test.ts -t "coachMessages" lib/sync/collect.test.ts -t "coachMessages"`
Expected: FAIL — `coachMessages` no está en los registros, así que apply lo ignora y collect no lo incluye.

- [ ] **Step 3: Register in apply** — in `lib/sync/apply.ts`, add to `TABLE_BY_NAME`:

```ts
  coachMessages: asSync(db.coachMessages),
```

- [ ] **Step 4: Register in collect** — in `lib/sync/collect.ts`, add to `SYNCABLE_TABLES` (sin `shouldSync`):

```ts
  { name: 'coachMessages', table: asSync(db.coachMessages) },
```

- [ ] **Step 5: Run, verify pass**

Run: `npx vitest run lib/sync/`
Expected: PASS (nuevos + previos). Also `npx tsc --noEmit`.

- [ ] **Step 6: Commit**

```bash
git add lib/sync/collect.ts lib/sync/apply.ts lib/sync/apply.test.ts lib/sync/collect.test.ts
git commit -m "feat(sync): coachMessages en collect/apply"
```

---

## Task 4: Backup — incluir `coachMessages`

**Files:**
- Modify: `lib/repositories/backup.ts`
- Modify: `lib/repositories/backup.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/backup.ts` (the `BackupFile` interface, `exportData` version `7` + data object, `importData` tables tuple + bulkPut block, and how `userSettings` was added in C0).

- [ ] **Step 1: Write the failing test** — append to `lib/repositories/backup.test.ts`:

```ts
it('exporta e importa coachMessages', async () => {
  await db.coachMessages.clear();
  await db.coachMessages.put({ id: 'b1', userId: null, rol: 'assistant', contenido: 'hey', createdAt: 5, updatedAt: 5, deletedAt: null });
  const backup = await exportData();
  expect(backup.data.coachMessages).toHaveLength(1);
  await db.coachMessages.clear();
  await importData(backup);
  expect((await db.coachMessages.get('b1'))?.contenido).toBe('hey');
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/repositories/backup.test.ts -t "coachMessages"`
Expected: FAIL — `backup.data.coachMessages` undefined.

- [ ] **Step 3: Implement** — in `lib/repositories/backup.ts`:

1. Add `CoachMessage` to the `import type { … } from '@/lib/db/types';`.
2. In `BackupFile.data`, add: `coachMessages: CoachMessage[];`
3. Bump `version: 7` → `version: 8` in `exportData`.
4. In `exportData`'s `data`, add: `coachMessages: await db.coachMessages.toArray(),`
5. In `importData`, add `db.coachMessages` to the `tables` tuple, and inside the transaction add: `if (d.coachMessages?.length) await db.coachMessages.bulkPut(d.coachMessages);`

(The generic updatedAt-refresh loop over `Object.values(d)` covers `coachMessages` automatically.)

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run lib/repositories/backup.test.ts`
Expected: PASS. Also `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/backup.ts lib/repositories/backup.test.ts
git commit -m "feat(backup): incluir coachMessages (version 8)"
```

---

## Task 5: Script de migración DDL para Neon

**Files:**
- Create: `scripts/migrate-coach-messages.mjs`

Context: el esquema de Neon se migra con **DDL directo** (no `drizzle-kit push`). Hay scripts previos (`scripts/migrate-user-settings.mjs`, `scripts/migrate-siguiente-rutina.mjs`) — **léelos primero** y replica su estructura exacta (carga de `.env.local`/`DATABASE_URL_UNPOOLED||DATABASE_URL`, cliente `@neondatabase/serverless`, ejecución, log de columnas + mensaje OK). Se ejecuta **al desplegar** (necesita el `DATABASE_URL` real); no forma parte de los tests.

- [ ] **Step 1: Read the reference scripts**

Run: `cat scripts/migrate-user-settings.mjs`

- [ ] **Step 2: Create `scripts/migrate-coach-messages.mjs`** mirroring that boilerplate, executing this idempotent DDL (columnas exactamente como `db/schema.ts`: `...sync` = id/user_id/updated_at/deleted_at/server_updated_at, más rol/contenido/created_at):

```sql
CREATE TABLE IF NOT EXISTS coach_messages (
  id text PRIMARY KEY,
  user_id text NOT NULL,
  rol text NOT NULL,
  contenido text NOT NULL,
  created_at bigint NOT NULL,
  updated_at bigint NOT NULL,
  deleted_at bigint,
  server_updated_at bigint NOT NULL
);
```

After the `CREATE TABLE`, add the same `information_schema.columns` verification query + log as the reference script. Read `DATABASE_URL` from env exactly like the reference; do NOT hardcode credentials.

- [ ] **Step 3: Lint + syntax check**

Run: `npm run lint && node --check scripts/migrate-coach-messages.mjs`
Expected: clean.

- [ ] **Step 4: Commit**

```bash
git add scripts/migrate-coach-messages.mjs
git commit -m "chore(db): script DDL Neon para tabla coach_messages"
```

> El script se EJECUTA en el deploy (`node scripts/migrate-coach-messages.mjs` con el `DATABASE_URL` real), antes de subir el código que lee/escribe la tabla. No se ejecuta en esta fase.

---

## Task 6: Verificación final

- [ ] **Step 1: Full suite**

Run: `npx vitest run`
Expected: todo verde (coach repo, sync coachMessages, backup coachMessages, previos).

- [ ] **Step 2: Types + lint**

Run: `npx tsc --noEmit && npm run lint`
Expected: sin errores.

- [ ] **Step 3: Build**

Run: `npm run build`
Expected: build OK (`--webpack`).

---

## Self-Review (hecho)

**Cobertura del spec (B1 / sección "Datos — entidad CoachMessage"):**
- Entidad `CoachMessage { userId, rol, contenido, createdAt }` extends SyncMeta → Task 1. ✓
- Dexie tabla `coachMessages: 'id, createdAt, deletedAt'` (v12) → Task 1. ✓
- Repo `listMessages`/`addMessage`/`clearThread` → Task 1. ✓
- Drizzle `coach_messages` (columnas sync estándar) → Task 2. ✓
- Registrar en los 3 registros de sync (server-tables, collect, apply) → Tasks 2, 3. ✓
- Backup → Task 4. ✓
- Migración DDL Neon → Task 5. ✓
- Tests: repo (orden, tombstone), sync (collect/apply), backup round-trip → Tasks 1, 3, 4. ✓

**Sin placeholders:** todo el código completo en cada paso.

**Consistencia de tipos:** `CoachMessage { id, userId: string|null, rol: 'user'|'assistant', contenido: string, createdAt, updatedAt, deletedAt }` idéntico en types.ts, repo, sync, backup y (sus columnas) en `db/schema.ts`/DDL. Tabla normal con id UUID → el push genérico (`target: table.id`) funciona sin cambios (a diferencia de la PK compuesta de C0). ✓

**Fuera de alcance de B1 (vienen en B2–B5):** `construirSnapshot`, ruta `/api/coach`, AI SDK/DeepSeek, UI de chat, accesos. B1 solo entrega la persistencia sincronizada del hilo.
