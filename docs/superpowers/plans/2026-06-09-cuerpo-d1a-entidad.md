# Cuerpo D1a — Entidad BodyMetric sincronizada (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Una entidad `BodyMetric` (peso corporal y medidas, como entradas tipadas) persistida y sincronizada, sobre la que D1b–D1d montan registro de métricas, pantalla `/cuerpo` y coach.

**Architecture:** Entidad sync estándar (UUID id, columnas sync comunes) — patrón idéntico a `coach_messages`/`gyms`. Tabla Dexie + repo + tabla Drizzle `body_metrics` + registro en los 3 registros de sync + backup + migración DDL en Neon.

**Tech Stack:** Next.js 16 + TypeScript · Dexie (IndexedDB) · Drizzle + Neon Postgres · Vitest + fake-indexeddb.

**Nota:** Fase **D1a** del spec `docs/superpowers/specs/2026-06-09-seguimiento-corporal-design.md`. Dexie va por v12 (coachMessages) y backup por v8 (de B1). Tabla NORMAL (id UUID), así que el push del servidor (`target: table.id`) funciona sin cambios. D1b–D1d en planes aparte.

---

## File Structure

- **Modify** `lib/db/types.ts` — interfaz `BodyMetric`.
- **Modify** `lib/db/database.ts` — tabla Dexie `bodyMetrics` (v13).
- **Create** `lib/repositories/body.ts` — `addMetric`/`listMetrics`/`listTipos`/`deleteMetric`.
- **Create** `lib/repositories/body.test.ts`.
- **Modify** `db/schema.ts` — tabla `bodyMetrics`.
- **Modify** `lib/sync/server-tables.ts` — registrar `bodyMetrics`.
- **Modify** `lib/sync/collect.ts` + `lib/sync/apply.ts` (+ tests) — registrar `bodyMetrics`.
- **Modify** `lib/repositories/backup.ts` (+ test) — incluir `bodyMetrics` (version 9).
- **Create** `scripts/migrate-body-metrics.mjs` — DDL Neon (se ejecuta al desplegar).

---

## Task 1: Entidad + tabla Dexie + repo

**Files:**
- Modify: `lib/db/types.ts`
- Modify: `lib/db/database.ts`
- Create: `lib/repositories/body.ts`
- Create: `lib/repositories/body.test.ts`

- [ ] **Step 1: Write the failing test** — `lib/repositories/body.test.ts`

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import 'fake-indexeddb/auto';
import { db } from '@/lib/db/database';
import { addMetric, listMetrics, listTipos, deleteMetric } from '@/lib/repositories/body';

beforeEach(async () => {
  await db.bodyMetrics.clear();
});

describe('body repo', () => {
  it('addMetric + listMetrics devuelve la serie de ese tipo en orden cronológico', async () => {
    await addMetric('peso', 78.4, 2000);
    await addMetric('peso', 78.0, 1000);
    const serie = await listMetrics('peso');
    expect(serie.map((m) => [m.fecha, m.valor])).toEqual([[1000, 78.0], [2000, 78.4]]);
  });

  it('listMetrics filtra por tipo', async () => {
    await addMetric('peso', 78, 1);
    await addMetric('cintura', 84, 1);
    expect((await listMetrics('peso')).map((m) => m.valor)).toEqual([78]);
    expect((await listMetrics('cintura')).map((m) => m.valor)).toEqual([84]);
  });

  it('addMetric genera id, marca sync y fecha por defecto = ahora', async () => {
    const antes = Date.now();
    const m = await addMetric('peso', 80);
    expect(m.id).toBeTruthy();
    expect(m.userId).toBeNull();
    expect(m.deletedAt).toBeNull();
    expect(m.fecha).toBeGreaterThanOrEqual(antes);
  });

  it('listTipos devuelve solo tipos con entradas activas', async () => {
    await addMetric('peso', 80, 1);
    await addMetric('cintura', 84, 1);
    const m = await addMetric('biceps', 38, 1);
    await deleteMetric(m.id);
    const tipos = (await listTipos()).sort();
    expect(tipos).toEqual(['cintura', 'peso']); // biceps borrado no aparece
  });

  it('deleteMetric hace tombstone (no aparece en listMetrics, fila sigue)', async () => {
    const m = await addMetric('peso', 80, 1);
    await deleteMetric(m.id);
    expect(await listMetrics('peso')).toHaveLength(0);
    expect(await db.bodyMetrics.count()).toBe(1);
  });
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/repositories/body.test.ts`
Expected: FAIL — módulo/tabla inexistente.

- [ ] **Step 3: Add the type** — in `lib/db/types.ts`, near the other entities:

```ts
export interface BodyMetric extends SyncMeta {
  userId: string | null;
  /** clave predefinida ('peso','cintura',...) o personalizada. */
  tipo: string;
  /** kg para peso, cm para medidas (o la unidad de la personalizada). */
  valor: number;
  /** epoch ms; ordena la serie. */
  fecha: number;
}
```

- [ ] **Step 4: Add the Dexie table** — in `lib/db/database.ts`:

1. Add `BodyMetric` to the existing `import type { … } from './types';`.
2. Declare the table field with the others: `bodyMetrics!: Table<BodyMetric, string>;`
3. After the `version(12)` block (coachMessages), add:

```ts
    // v13: métricas corporales (peso + medidas), sincronizadas.
    this.version(13).stores({
      bodyMetrics: 'id, tipo, fecha, deletedAt',
    });
```

- [ ] **Step 5: Implement the repo** — `lib/repositories/body.ts`

```ts
import { db } from '@/lib/db/database';
import type { BodyMetric } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

/** Entradas activas de un tipo, en orden cronológico ascendente. */
export async function listMetrics(tipo: string): Promise<BodyMetric[]> {
  const all = activo(await db.bodyMetrics.where('tipo').equals(tipo).toArray());
  return all.sort((a, b) => a.fecha - b.fecha);
}

/** Tipos de métrica con al menos una entrada activa. */
export async function listTipos(): Promise<string[]> {
  const all = activo(await db.bodyMetrics.toArray());
  return [...new Set(all.map((m) => m.tipo))];
}

/** Añade una medición. `fecha` por defecto = ahora. */
export async function addMetric(tipo: string, valor: number, fecha: number = now()): Promise<BodyMetric> {
  const m: BodyMetric = {
    id: crypto.randomUUID(),
    userId: null,
    tipo,
    valor,
    fecha,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.bodyMetrics.put(m);
  return m;
}

/** Borra (tombstone) una medición. */
export async function deleteMetric(id: string): Promise<void> {
  const ts = now();
  await db.bodyMetrics.update(id, { deletedAt: ts, updatedAt: ts });
}
```

- [ ] **Step 6: Run, verify pass**

Run: `npx vitest run lib/repositories/body.test.ts`
Expected: PASS (5). Also `npx tsc --noEmit` clean.

- [ ] **Step 7: Commit**

```bash
git add lib/db/types.ts lib/db/database.ts lib/repositories/body.ts lib/repositories/body.test.ts
git commit -m "feat(cuerpo): entidad BodyMetric + tabla Dexie v13 + repo"
```

---

## Task 2: Servidor — tabla `body_metrics` + registro

**Files:**
- Modify: `db/schema.ts`
- Modify: `lib/sync/server-tables.ts`

- [ ] **Step 0: READ FIRST**

Read `db/schema.ts` (the shared `sync` columns, how `coachMessages`/`gyms` use `...sync` + extra columns, and that `doublePrecision`/`text`/`bigint` are imported). Read `lib/sync/server-tables.ts`.

- [ ] **Step 1: Add the Drizzle table** — in `db/schema.ts`, at the END (standard `...sync`; `valor` is `doublePrecision` because medidas/peso admiten decimales):

```ts
export const bodyMetrics = pgTable('body_metrics', {
  ...sync,
  tipo: text('tipo').notNull(),
  valor: doublePrecision('valor').notNull(),
  fecha: bigint('fecha', { mode: 'number' }).notNull(),
});
```

- [ ] **Step 2: Register it** — in `lib/sync/server-tables.ts`, add to `SERVER_TABLES`:

```ts
  bodyMetrics: schema.bodyMetrics,
```

- [ ] **Step 3: Typecheck + lint + suite**

Run: `npx tsc --noEmit && npm run lint && npx vitest run`
Expected: clean/green. (No live-DB test; the push route is generic `target: table.id` — works for this standard table without changes.)

- [ ] **Step 4: Commit**

```bash
git add db/schema.ts lib/sync/server-tables.ts
git commit -m "feat(sync): tabla body_metrics (servidor) + registro en SERVER_TABLES"
```

---

## Task 3: Cliente sync — registrar `bodyMetrics` en collect + apply

**Files:**
- Modify: `lib/sync/collect.ts`
- Modify: `lib/sync/apply.ts`
- Modify: `lib/sync/apply.test.ts`
- Modify: `lib/sync/collect.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/sync/collect.ts` (`SYNCABLE_TABLES`, `asSync`) and `lib/sync/apply.ts` (`TABLE_BY_NAME`) — note the `coachMessages`/`userSettings` entries. Read `lib/sync/apply.test.ts` + `lib/sync/collect.test.ts` (the `coachMessages` tests added in B1, incl. the `as unknown as SyncMeta[]` cast idiom).

- [ ] **Step 1: Write the failing tests**

Append to `lib/sync/apply.test.ts`:
```ts
it('bodyMetrics se aplican por id (LWW)', async () => {
  await db.bodyMetrics.clear();
  await applyIncoming([{ table: 'bodyMetrics', records: [
    { id: 'bm1', userId: 'u1', tipo: 'peso', valor: 80, fecha: 100, updatedAt: 100, deletedAt: null },
  ] as unknown as import('@/lib/db/types').SyncMeta[] }]);
  const m = await db.bodyMetrics.get('bm1');
  expect(m?.valor).toBe(80);
  expect(await db.bodyMetrics.count()).toBe(1);
});
```
Append to `lib/sync/collect.test.ts`:
```ts
it('sincroniza bodyMetrics', async () => {
  await db.bodyMetrics.clear();
  await db.bodyMetrics.put({ id: 'bm-c1', userId: null, tipo: 'peso', valor: 80, fecha: 1, updatedAt: 1000, deletedAt: null });
  const changes = await collectDirty(0);
  expect(changes.find((c) => c.table === 'bodyMetrics')?.records).toHaveLength(1);
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/sync/apply.test.ts -t "bodyMetrics"` and `npx vitest run lib/sync/collect.test.ts -t "bodyMetrics"`
Expected: FAIL — no registrado.

- [ ] **Step 3: Register in apply** — `lib/sync/apply.ts`, add to `TABLE_BY_NAME`:
```ts
  bodyMetrics: asSync(db.bodyMetrics),
```

- [ ] **Step 4: Register in collect** — `lib/sync/collect.ts`, add to `SYNCABLE_TABLES` (sin `shouldSync`):
```ts
  { name: 'bodyMetrics', table: asSync(db.bodyMetrics) },
```

- [ ] **Step 5: Run, verify pass**

Run: `npx vitest run lib/sync/`
Expected: PASS. Also `npx tsc --noEmit`.

- [ ] **Step 6: Commit**

```bash
git add lib/sync/collect.ts lib/sync/apply.ts lib/sync/apply.test.ts lib/sync/collect.test.ts
git commit -m "feat(sync): bodyMetrics en collect/apply"
```

---

## Task 4: Backup — incluir `bodyMetrics`

**Files:**
- Modify: `lib/repositories/backup.ts`
- Modify: `lib/repositories/backup.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/backup.ts` (the `BackupFile` interface, `exportData` current `version: 8`, the data object, `importData` tables tuple + bulkPut, and how `coachMessages` was added in B1).

- [ ] **Step 1: Write the failing test** — append to `lib/repositories/backup.test.ts`:

```ts
it('exporta e importa bodyMetrics', async () => {
  await db.bodyMetrics.clear();
  await db.bodyMetrics.put({ id: 'bk1', userId: null, tipo: 'peso', valor: 79.5, fecha: 5, updatedAt: 5, deletedAt: null });
  const backup = await exportData();
  expect(backup.data.bodyMetrics).toHaveLength(1);
  await db.bodyMetrics.clear();
  await importData(backup);
  expect((await db.bodyMetrics.get('bk1'))?.valor).toBe(79.5);
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/repositories/backup.test.ts -t "bodyMetrics"`
Expected: FAIL — `backup.data.bodyMetrics` undefined.

- [ ] **Step 3: Implement** — in `lib/repositories/backup.ts`:
1. Add `BodyMetric` to the `import type { … } from '@/lib/db/types';`.
2. In `BackupFile.data`, add: `bodyMetrics: BodyMetric[];`
3. Bump `version: 8` → `version: 9` in `exportData`.
4. In `exportData`'s `data`, add: `bodyMetrics: await db.bodyMetrics.toArray(),`
5. In `importData`, add `db.bodyMetrics` to the `tables` tuple, and inside the transaction add: `if (d.bodyMetrics?.length) await db.bodyMetrics.bulkPut(d.bodyMetrics);`

(The generic updatedAt-refresh loop over `Object.values(d)` covers `bodyMetrics` automatically. If a hand-rolled `BackupFile` fixture in the tests now lacks the required `bodyMetrics` field and tsc complains, add `bodyMetrics: []` to it — legit fixture fix, as done for coachMessages.)

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run lib/repositories/backup.test.ts`
Expected: PASS. Also `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/backup.ts lib/repositories/backup.test.ts
git commit -m "feat(backup): incluir bodyMetrics (version 9)"
```

---

## Task 5: Script de migración DDL para Neon

**Files:**
- Create: `scripts/migrate-body-metrics.mjs`

Context: DDL directo (no drizzle-kit push). **Lee primero** `scripts/migrate-coach-messages.mjs` y replica su estructura exacta (carga `.env.local`, `DATABASE_URL_UNPOOLED||DATABASE_URL`, cliente `@neondatabase/serverless`, `CREATE TABLE IF NOT EXISTS` + verificación `information_schema.columns` + log). Se ejecuta al desplegar; no es parte de los tests.

- [ ] **Step 1: Read the reference**

Run: `cat scripts/migrate-coach-messages.mjs`

- [ ] **Step 2: Create `scripts/migrate-body-metrics.mjs`** mirroring it, executing this idempotent DDL (columnas como `db/schema.ts`: `...sync` = id/user_id/updated_at/deleted_at/server_updated_at, más tipo/valor/fecha; `valor` es double precision):

```sql
CREATE TABLE IF NOT EXISTS body_metrics (
  id text PRIMARY KEY,
  user_id text NOT NULL,
  tipo text NOT NULL,
  valor double precision NOT NULL,
  fecha bigint NOT NULL,
  updated_at bigint NOT NULL,
  deleted_at bigint,
  server_updated_at bigint NOT NULL
);
```
Add the same `information_schema.columns` verification query (filter `table_name = 'body_metrics'`) + log. Read `DATABASE_URL` from env like the reference; NO hardcoded credentials.

- [ ] **Step 3: Lint + syntax check**

Run: `npm run lint && node --check scripts/migrate-body-metrics.mjs`
Expected: clean.

- [ ] **Step 4: Commit**

```bash
git add scripts/migrate-body-metrics.mjs
git commit -m "chore(db): script DDL Neon para tabla body_metrics"
```

> Se EJECUTA en el deploy (`node scripts/migrate-body-metrics.mjs` con el `DATABASE_URL` real) antes de subir código que use la tabla. No en esta fase.

---

## Task 6: Verificación final

- [ ] **Step 1: Full suite**

Run: `npx vitest run`
Expected: todo verde (body repo, sync bodyMetrics, backup bodyMetrics, previos).

- [ ] **Step 2: Types + lint**

Run: `npx tsc --noEmit && npm run lint`
Expected: sin errores.

- [ ] **Step 3: Build**

Run: `npm run build`
Expected: build OK (`--webpack`).

---

## Self-Review (hecho)

**Cobertura del spec (D1a / "Datos — entidad BodyMetric"):**
- `BodyMetric { userId, tipo, valor, fecha }` extends SyncMeta → Task 1. ✓
- Dexie `bodyMetrics: 'id, tipo, fecha, deletedAt'` (v13) → Task 1. ✓
- Repo `addMetric`/`listMetrics`/`listTipos`/`deleteMetric` → Task 1. ✓
- Drizzle `body_metrics` (`...sync` + tipo/valor double/fecha) → Task 2. ✓
- Registro en los 3 registros de sync → Tasks 2, 3. ✓
- Backup → Task 4. ✓
- Migración DDL Neon → Task 5. ✓
- Tests: repo (orden/filtro/listTipos/tombstone), sync (collect/apply), backup → Tasks 1, 3, 4. ✓

**Sin placeholders:** código completo en cada paso.

**Consistencia de tipos:** `BodyMetric { id, userId: string|null, tipo: string, valor: number, fecha: number, updatedAt, deletedAt }` idéntico en types.ts, repo, sync, backup y (columnas) en schema/DDL. `valor` es `number`/`double precision` (admite decimales: peso 78,4). Tabla normal id UUID → push genérico sin cambios. ✓

**Fuera de alcance de D1a (vienen en D1b–D1d):** `METRICAS_PREDEF`/`resolverMetrica`/personalizadas, pantalla `/cuerpo`, integración coach. D1a solo entrega la persistencia sincronizada.
