# Insights C0 — Ajustes sincronizados (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un store de ajustes clave-valor **sincronizado** entre dispositivos, sobre el que migran los ajustes de A (modo de progresión, incrementos) y del que colgarán los ajustes de C2/C3.

**Architecture:** Entidad sincronizable `UserSetting` cuyo `id` ES la clave del ajuste (convergencia LWW entre dispositivos sin filas duplicadas). Repo `getSetting`/`setSetting` sobre Dexie + hook reactivo `useSetting` (useLiveQuery, se actualiza al hacer pull). Server: tabla `user_settings` con PK compuesta `(user_id, id)` y upsert con conflict-target compuesto solo para esa tabla. Migración idempotente de los ajustes de A desde localStorage.

**Tech Stack:** Next.js 16 + React 19 + TypeScript · Dexie (IndexedDB) · Drizzle + Neon Postgres · Vitest + fake-indexeddb.

**Nota:** Es la fase **C0** del spec `docs/superpowers/specs/2026-06-08-insights-design.md` (módulo 0). C1–C3 vienen en planes aparte. No toca el schema de entrenos; el único cambio de datos es `userSettings`/`user_settings`.

---

## File Structure

- **Modify** `lib/db/types.ts` — interfaz `UserSetting`.
- **Modify** `lib/db/database.ts` — tabla Dexie `userSettings` (v11).
- **Create** `lib/repositories/user-settings.ts` — `getSetting`/`setSetting`/`deleteSetting` + `migrarAjustesLocales`.
- **Create** `lib/repositories/user-settings.test.ts`.
- **Create** `lib/use-setting.ts` — hook `useSetting<T>`.
- **Create** `lib/use-setting.test.tsx`.
- **Modify** `lib/settings.ts` — `useModoProgresion`/`useIncrementos` pasan a `useSetting`; se elimina su código localStorage. `useSuggestNextRoutine` intacto.
- **Modify** `lib/settings.test.ts` — quitar tests de modo/incrementos (migrados).
- **Modify** `components/sync-provider.tsx` — llamar `migrarAjustesLocales()` una vez.
- **Modify** `db/schema.ts` — tabla `userSettings` (PK compuesta).
- **Modify** `lib/sync/server-tables.ts` — registrar `userSettings`.
- **Modify** `app/api/sync/push/route.ts` — conflict-target compuesto para `userSettings`.
- **Modify** `lib/sync/collect.ts` + `lib/sync/apply.ts` (+ tests) — registrar `userSettings`.
- **Modify** `lib/repositories/backup.ts` (+ test) — incluir `userSettings` (version 7).
- **Create** `scripts/migrate-user-settings.mjs` — DDL Neon (se ejecuta al desplegar).

---

## Task 1: Entidad + tabla Dexie + repo

**Files:**
- Modify: `lib/db/types.ts`
- Modify: `lib/db/database.ts`
- Create: `lib/repositories/user-settings.ts`
- Create: `lib/repositories/user-settings.test.ts`

- [ ] **Step 1: Write the failing test** — `lib/repositories/user-settings.test.ts`

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import 'fake-indexeddb/auto';
import { db } from '@/lib/db/database';
import { getSetting, setSetting, deleteSetting } from '@/lib/repositories/user-settings';

beforeEach(async () => {
  await db.userSettings.clear();
});

describe('user-settings repo', () => {
  it('round-trip de un número', async () => {
    await setSetting('objetivoSemanal', 4);
    expect(await getSetting<number>('objetivoSemanal')).toBe(4);
  });
  it('round-trip de un objeto', async () => {
    await setSetting('incrementos', { barra: 2.5, maquina: 5 });
    expect(await getSetting<Record<string, number>>('incrementos')).toEqual({ barra: 2.5, maquina: 5 });
  });
  it('clave inexistente → undefined', async () => {
    expect(await getSetting('nada')).toBeUndefined();
  });
  it('setSetting reescribe y sube updatedAt', async () => {
    await setSetting('x', 1);
    const a = await db.userSettings.get('x');
    await setSetting('x', 2);
    const b = await db.userSettings.get('x');
    expect(await getSetting<number>('x')).toBe(2);
    expect(b!.updatedAt).toBeGreaterThanOrEqual(a!.updatedAt);
  });
  it('deleteSetting (tombstone) → getSetting undefined', async () => {
    await setSetting('x', 1);
    await deleteSetting('x');
    expect(await getSetting('x')).toBeUndefined();
    const row = await db.userSettings.get('x');
    expect(row!.deletedAt).not.toBeNull(); // sigue en la tabla como tombstone
  });
  it('valor corrupto → undefined', async () => {
    await db.userSettings.put({ id: 'roto', userId: null, valor: '{no json', updatedAt: 1, deletedAt: null });
    expect(await getSetting('roto')).toBeUndefined();
  });
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/repositories/user-settings.test.ts`
Expected: FAIL — módulo/tipo inexistente.

- [ ] **Step 3: Add the type** — in `lib/db/types.ts`, after `SyncState` (or near the other entities):

```ts
export interface UserSetting extends SyncMeta {
  /** null en local hasta el primer push (igual que el resto de entidades). */
  userId: string | null;
  /** Valor serializado como JSON (número, booleano u objeto). */
  valor: string;
}
```

- [ ] **Step 4: Add the Dexie table** — in `lib/db/database.ts`:

1. Import the type: add `UserSetting` to the existing `import type { … } from './types';`.
2. Declare the table field with the others: `userSettings!: Table<UserSetting, string>;`
3. After the `version(10)` block (before the closing of the constructor), add:

```ts
    // v11: store de ajustes sincronizados (id = clave del ajuste).
    this.version(11).stores({
      userSettings: 'id, deletedAt',
    });
```

- [ ] **Step 5: Implement the repo** — `lib/repositories/user-settings.ts`

```ts
import { db } from '@/lib/db/database';
import type { UserSetting } from '@/lib/db/types';

const now = () => Date.now();

/** Lee un ajuste por su clave (id). undefined si no existe, está borrado o el JSON es inválido. */
export async function getSetting<T>(clave: string): Promise<T | undefined> {
  const row = await db.userSettings.get(clave);
  if (!row || row.deletedAt !== null) return undefined;
  try {
    return JSON.parse(row.valor) as T;
  } catch {
    return undefined;
  }
}

/** Upsert de un ajuste (id = clave). Serializa el valor a JSON y sube updatedAt. */
export async function setSetting<T>(clave: string, valor: T): Promise<void> {
  const existing = await db.userSettings.get(clave);
  const row: UserSetting = {
    id: clave,
    userId: existing?.userId ?? null,
    valor: JSON.stringify(valor),
    updatedAt: now(),
    deletedAt: null,
  };
  await db.userSettings.put(row);
}

/** Borra (tombstone) un ajuste; getSetting volverá a devolver undefined. */
export async function deleteSetting(clave: string): Promise<void> {
  const ts = now();
  await db.userSettings.update(clave, { deletedAt: ts, updatedAt: ts });
}
```

- [ ] **Step 6: Run, verify pass**

Run: `npx vitest run lib/repositories/user-settings.test.ts`
Expected: PASS (6 tests). Also `npx tsc --noEmit` clean.

- [ ] **Step 7: Commit**

```bash
git add lib/db/types.ts lib/db/database.ts lib/repositories/user-settings.ts lib/repositories/user-settings.test.ts
git commit -m "feat(ajustes): entidad UserSetting + tabla Dexie v11 + repo getSetting/setSetting"
```

---

## Task 2: Hook `useSetting`

**Files:**
- Create: `lib/use-setting.ts`
- Create: `lib/use-setting.test.tsx`

- [ ] **Step 1: Write the failing test** — `lib/use-setting.test.tsx`

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import 'fake-indexeddb/auto';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { useSetting } from '@/lib/use-setting';

beforeEach(async () => {
  await db.userSettings.clear();
});

function Probe() {
  const [valor, set] = useSetting<number>('objetivoSemanal', 3);
  return (
    <div>
      <span data-testid="valor">{valor}</span>
      <button onClick={() => set(5)}>set5</button>
    </div>
  );
}

describe('useSetting', () => {
  it('devuelve el fallback cuando no hay valor y persiste/reacciona al cambiar', async () => {
    render(<Probe />);
    expect(screen.getByTestId('valor').textContent).toBe('3'); // fallback
    await userEvent.click(screen.getByRole('button', { name: 'set5' }));
    await waitFor(() => expect(screen.getByTestId('valor').textContent).toBe('5'));
  });
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/use-setting.test.tsx`
Expected: FAIL — módulo inexistente.

- [ ] **Step 3: Implement** — `lib/use-setting.ts`

```ts
'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { getSetting, setSetting } from '@/lib/repositories/user-settings';

/**
 * Ajuste sincronizado reactivo. Devuelve `fallback` mientras carga o si no existe.
 * El valor se actualiza solo al cambiarlo localmente Y cuando llega por sync (pull).
 */
export function useSetting<T>(clave: string, fallback: T): [T, (v: T) => void] {
  const value = useLiveQuery(() => getSetting<T>(clave), [clave]);
  const set = (v: T) => { void setSetting(clave, v); };
  return [value ?? fallback, set];
}
```

(Nota: `value ?? fallback` solo cae al fallback con `null`/`undefined`; valores falsy válidos como `0` o `false` se conservan.)

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run lib/use-setting.test.tsx`
Expected: PASS. Also `npx tsc --noEmit` clean.

- [ ] **Step 5: Commit**

```bash
git add lib/use-setting.ts lib/use-setting.test.tsx
git commit -m "feat(ajustes): hook reactivo useSetting sobre Dexie"
```

---

## Task 3: Migración idempotente de los ajustes de A desde localStorage

**Files:**
- Modify: `lib/repositories/user-settings.ts`
- Modify: `lib/repositories/user-settings.test.ts`
- Modify: `components/sync-provider.tsx`

- [ ] **Step 1: Write the failing test** — append to `lib/repositories/user-settings.test.ts`

```ts
import { migrarAjustesLocales } from '@/lib/repositories/user-settings';

describe('migrarAjustesLocales', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('siembra modo e incrementos desde localStorage', async () => {
    localStorage.setItem('gymlog.modoProgresion', 'doble');
    localStorage.setItem('gymlog.incrementos', JSON.stringify({ maquina: 7.5 }));
    await migrarAjustesLocales();
    expect(await getSetting<string>('modoProgresion')).toBe('doble');
    expect(await getSetting<Record<string, number>>('incrementos')).toEqual({ maquina: 7.5 });
  });

  it('es idempotente: no pisa un valor ya existente', async () => {
    await setSetting('modoProgresion', 'off');
    localStorage.setItem('gymlog.modoProgresion', 'doble');
    await migrarAjustesLocales();
    expect(await getSetting<string>('modoProgresion')).toBe('off'); // no lo pisó
  });

  it('sin nada en localStorage no crea filas', async () => {
    await migrarAjustesLocales();
    expect(await db.userSettings.count()).toBe(0);
  });
});
```

(`jsdom` provee `localStorage`; el proyecto de tests `app` corre en jsdom — ver `vitest.config.ts`. Este fichero ya importa `fake-indexeddb/auto`.)

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/repositories/user-settings.test.ts -t "migrarAjustesLocales"`
Expected: FAIL — función inexistente.

- [ ] **Step 3: Implement** — append to `lib/repositories/user-settings.ts`

```ts
/**
 * Migra una sola vez los ajustes de la fase A que vivían en localStorage al store
 * sincronizado. Idempotente: si la clave ya existe en userSettings, no la toca.
 */
export async function migrarAjustesLocales(): Promise<void> {
  if (typeof localStorage === 'undefined') return;
  const fuentes: { clave: string; lsKey: string; parse: (raw: string) => unknown }[] = [
    { clave: 'modoProgresion', lsKey: 'gymlog.modoProgresion', parse: (raw) => raw },
    { clave: 'incrementos', lsKey: 'gymlog.incrementos', parse: (raw) => JSON.parse(raw) },
  ];
  for (const { clave, lsKey, parse } of fuentes) {
    const raw = localStorage.getItem(lsKey);
    if (raw == null) continue;
    if (await db.userSettings.get(clave)) continue; // ya migrado → no pisar
    try {
      await setSetting(clave, parse(raw));
    } catch {
      // valor corrupto en localStorage: se ignora
    }
  }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run lib/repositories/user-settings.test.ts`
Expected: PASS (todos).

- [ ] **Step 5: Wire it into app init** — in `components/sync-provider.tsx`, add a one-time effect that runs regardless of auth (hooks run before the `if (!isSignedIn) return null`). Add the import and a new effect after the existing `useEffect`:

```ts
import { migrarAjustesLocales } from '@/lib/repositories/user-settings';
```

```ts
  useEffect(() => {
    void migrarAjustesLocales();
  }, []);
```

- [ ] **Step 6: Verify nothing broke**

Run: `npx vitest run && npx tsc --noEmit`
Expected: green / clean.

- [ ] **Step 7: Commit**

```bash
git add lib/repositories/user-settings.ts lib/repositories/user-settings.test.ts components/sync-provider.tsx
git commit -m "feat(ajustes): migración idempotente de modo/incrementos desde localStorage"
```

---

## Task 4: Migrar `useModoProgresion`/`useIncrementos` a `useSetting`

**Files:**
- Modify: `lib/settings.ts`
- Modify: `lib/settings.test.ts`

Context: hoy `lib/settings.ts` guarda modo/incrementos en localStorage (con `getModoProgresion`/`setModoProgresion`/`getIncrementos`/`setIncrementos` + caché `_incrCache`). Pasan a apoyarse en `useSetting` (Dexie, sincronizado). `useSuggestNextRoutine` y su `EVENT`/`subscribe` se mantienen igual.

- [ ] **Step 1: Verify consumers** — confirm only the hooks are used (not the standalone get/set) outside tests:

Run: `grep -rn "getModoProgresion\|setModoProgresion\|getIncrementos\|setIncrementos" lib app components | grep -v ".test."`
Expected: no matches outside `lib/settings.ts` itself. (Consumers — la card de entreno y la página de Ajustes de A — usan los hooks `useModoProgresion`/`useIncrementos`.) If any non-hook consumer appears, convert it to the hook or to `getSetting`; report it.

- [ ] **Step 2: Update the tests first** — in `lib/settings.test.ts`, REMOVE the two describes `'modo de progresión'` and `'incrementos por equipamiento'` (their behavior now lives in `user-settings.test.ts`/`use-setting.test.tsx`). Keep the `getSuggestNextRoutine`/`useSuggestNextRoutine` tests. Also remove the now-unused imports (`getModoProgresion`, `setModoProgresion`, `getIncrementos`, `setIncrementos`, `INCREMENTO_DEFAULTS`) from that test file.

- [ ] **Step 3: Run, verify the suite still references valid symbols**

Run: `npx vitest run lib/settings.test.ts`
Expected: PASS (solo los tests de suggestNextRoutine). (Si falla por símbolos aún exportados, continúa al paso 4.)

- [ ] **Step 4: Rewrite the hooks** — in `lib/settings.ts`:

1. Remove: `KEY_MODO`, `KEY_INCR`, `MODOS` (keep a local `MODOS` for validation — see below), `DEFAULT_MODO`, `SERVER_INCR`, `_incrCache`/`_incrCacheRaw`, and the functions `getModoProgresion`/`setModoProgresion`/`getIncrementos`/`setIncrementos` and the old hook bodies.
2. Keep: everything for `useSuggestNextRoutine` (`KEY`, `EVENT`, `subscribe`, `getSuggestNextRoutine`, `setSuggestNextRoutine`, `useSuggestNextRoutine`, and the `useSyncExternalStore` import it needs).
3. Add the new hook implementations:

```ts
import { useSetting } from '@/lib/use-setting';
import type { Equipment } from '@/lib/db/types';
import type { ModoProgresion } from '@/lib/progresion';
import { INCREMENTO_DEFAULTS } from '@/lib/progresion';

const MODOS: ModoProgresion[] = ['doble', 'objetivo', 'repite', 'off'];

/** Modo de progresión (sincronizado). Default 'objetivo'; valida contra MODOS. */
export function useModoProgresion(): [ModoProgresion, (v: ModoProgresion) => void] {
  const [raw, set] = useSetting<ModoProgresion>('modoProgresion', 'objetivo');
  const value = MODOS.includes(raw) ? raw : 'objetivo';
  return [value, set];
}

/** Incrementos por equipamiento (sincronizado). Se fusiona sobre los defaults. */
export function useIncrementos(): [Record<Equipment, number>, (p: Partial<Record<Equipment, number>>) => void] {
  const [stored, set] = useSetting<Partial<Record<Equipment, number>>>('incrementos', {});
  const value = { ...INCREMENTO_DEFAULTS, ...stored };
  const setPartial = (p: Partial<Record<Equipment, number>>) => set({ ...stored, ...p });
  return [value, setPartial];
}
```

- [ ] **Step 5: Run full suite + types + lint**

Run: `npx vitest run && npx tsc --noEmit && npm run lint`
Expected: all green/clean. In particular the A tests (`components/logged-exercise-card.test.tsx`, the ajustes page) still pass: the hooks keep the same `[value, setter]` signature; with no `userSettings` row seeded they return the defaults (`'objetivo'`, `INCREMENTO_DEFAULTS`).

- [ ] **Step 6: Commit**

```bash
git add lib/settings.ts lib/settings.test.ts
git commit -m "refactor(ajustes): modo/incrementos pasan a useSetting (sincronizados)"
```

---

## Task 5: Servidor — tabla `user_settings` + registro + upsert compuesto

**Files:**
- Modify: `db/schema.ts`
- Modify: `lib/sync/server-tables.ts`
- Modify: `app/api/sync/push/route.ts`

- [ ] **Step 1: Add the Drizzle table** — in `db/schema.ts`:

1. Extend the import: `import { pgTable, text, doublePrecision, integer, bigint, boolean, primaryKey } from 'drizzle-orm/pg-core';`
2. At the end of the file add (NO usa el spread `sync` porque `id` aquí no es PK simple sino parte de la PK compuesta):

```ts
export const userSettings = pgTable('user_settings', {
  id: text('id').notNull(),
  userId: text('user_id').notNull(),
  valor: text('valor').notNull(),
  updatedAt: bigint('updated_at', { mode: 'number' }).notNull(),
  deletedAt: bigint('deleted_at', { mode: 'number' }),
  serverUpdatedAt: bigint('server_updated_at', { mode: 'number' }).notNull(),
}, (t) => ({
  pk: primaryKey({ columns: [t.userId, t.id] }),
}));
```

- [ ] **Step 2: Register it** — in `lib/sync/server-tables.ts`, add to `SERVER_TABLES`:

```ts
  userSettings: schema.userSettings,
```

- [ ] **Step 3: Composite conflict target in push** — in `app/api/sync/push/route.ts`, inside the `for (const rec ...)` loop, replace the upsert line:

```ts
      const values = { ...rec, userId, serverUpdatedAt };
      await db.insert(table).values(values).onConflictDoUpdate({ target: table.id, set: values });
```

with:

```ts
      const values = { ...rec, userId, serverUpdatedAt };
      // user_settings tiene PK compuesta (user_id, id); las demás tablas, id simple.
      const conflictTarget = name === 'userSettings' ? [table.userId, table.id] : table.id;
      await db.insert(table).values(values).onConflictDoUpdate({ target: conflictTarget, set: values });
```

(The existing existence-check `where(and(eq(table.id, rec.id), eq(table.userId, userId)))` already scopes per-user, so it works unchanged for `userSettings`.)

- [ ] **Step 4: Typecheck + lint + suite**

Run: `npx tsc --noEmit && npm run lint && npx vitest run`
Expected: clean/green. (No hay test de integración contra Neon; los tests de sync usan transporte en memoria.)

- [ ] **Step 5: Commit**

```bash
git add db/schema.ts lib/sync/server-tables.ts app/api/sync/push/route.ts
git commit -m "feat(sync): tabla user_settings (PK compuesta) + upsert compuesto en push"
```

---

## Task 6: Cliente sync — registrar `userSettings` en collect + apply

**Files:**
- Modify: `lib/sync/collect.ts`
- Modify: `lib/sync/apply.ts`
- Modify: `lib/sync/apply.test.ts`

- [ ] **Step 1: Write the failing test** — append to `lib/sync/apply.test.ts` (mirror the file's existing import/setup style; it likely imports `applyIncoming`/`pickWinner` and uses fake-indexeddb):

```ts
it('userSettings converge por id-clave (LWW)', async () => {
  await db.userSettings.clear();
  // estado local: modoProgresion = "off" con updatedAt 100
  await db.userSettings.put({ id: 'modoProgresion', userId: 'u1', valor: '"off"', updatedAt: 100, deletedAt: null });
  // llega del server el mismo ajuste, más nuevo
  await applyIncoming([{ table: 'userSettings', records: [
    { id: 'modoProgresion', userId: 'u1', valor: '"doble"', updatedAt: 200, deletedAt: null },
  ] }]);
  const row = await db.userSettings.get('modoProgresion');
  expect(row!.valor).toBe('"doble"'); // gana el updatedAt mayor
  expect(await db.userSettings.count()).toBe(1); // mismo id → no duplica
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/sync/apply.test.ts -t "userSettings converge"`
Expected: FAIL — `applyIncoming` ignora la tabla `userSettings` (no está en `TABLE_BY_NAME`), así que no escribe el valor nuevo.

- [ ] **Step 3: Register in apply** — in `lib/sync/apply.ts`, add to `TABLE_BY_NAME`:

```ts
  userSettings: asSync(db.userSettings),
```

- [ ] **Step 4: Register in collect** — in `lib/sync/collect.ts`, add to `SYNCABLE_TABLES` (sin `shouldSync`: todos los ajustes se sincronizan):

```ts
  { name: 'userSettings', table: asSync(db.userSettings) },
```

- [ ] **Step 5: Run, verify pass**

Run: `npx vitest run lib/sync/`
Expected: PASS (nuevo test + los previos de collect/apply/sync). Also `npx tsc --noEmit`.

- [ ] **Step 6: Commit**

```bash
git add lib/sync/collect.ts lib/sync/apply.ts lib/sync/apply.test.ts
git commit -m "feat(sync): userSettings en collect/apply (convergencia LWW por clave)"
```

---

## Task 7: Backup — incluir `userSettings`

**Files:**
- Modify: `lib/repositories/backup.ts`
- Modify: `lib/repositories/backup.test.ts`

- [ ] **Step 1: Write the failing test** — append to `lib/repositories/backup.test.ts` (mirror its setup; it imports `exportData`/`importData`):

```ts
it('exporta e importa userSettings', async () => {
  await db.userSettings.clear();
  await db.userSettings.put({ id: 'objetivoSemanal', userId: null, valor: '4', updatedAt: 1, deletedAt: null });
  const backup = await exportData();
  expect(backup.data.userSettings).toHaveLength(1);
  await db.userSettings.clear();
  await importData(backup);
  const row = await db.userSettings.get('objetivoSemanal');
  expect(row?.valor).toBe('4');
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/repositories/backup.test.ts -t "userSettings"`
Expected: FAIL — `backup.data.userSettings` undefined.

- [ ] **Step 3: Implement** — in `lib/repositories/backup.ts`:

1. Import the type: add `UserSetting` to the `import type { … } from '@/lib/db/types';`.
2. In `BackupFile.data`, add: `userSettings: UserSetting[];`
3. Bump `version` from `6` to `7` in `exportData`.
4. In `exportData`'s `data`, add: `userSettings: await db.userSettings.toArray(),`
5. In `importData`, add `db.userSettings` to the `tables` tuple, and inside the transaction add: `if (d.userSettings?.length) await db.userSettings.bulkPut(d.userSettings);`

(El bucle que refresca `updatedAt` itera `Object.values(d)` genéricamente, así que cubre `userSettings` sin cambios.)

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run lib/repositories/backup.test.ts`
Expected: PASS. Also `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/backup.ts lib/repositories/backup.test.ts
git commit -m "feat(backup): incluir userSettings (version 7)"
```

---

## Task 8: Script de migración DDL para Neon

**Files:**
- Create: `scripts/migrate-user-settings.mjs`

Context: el esquema de Neon se migra con **DDL directo** (no `drizzle-kit push`, que es interactivo). Hay scripts previos (p. ej. `scripts/migrate-siguiente-rutina.mjs`) — **léelo primero** y replica su estructura exacta (carga de `.env`/`DATABASE_URL`, cliente Neon/pg, ejecución, logs). Este script se ejecuta **al desplegar** (necesita el `DATABASE_URL` real); no forma parte de la suite de tests.

- [ ] **Step 1: Read the existing script** to copy its boilerplate

Run: `cat scripts/migrate-siguiente-rutina.mjs`

- [ ] **Step 2: Create `scripts/migrate-user-settings.mjs`** mirroring that boilerplate, executing this idempotent DDL:

```sql
CREATE TABLE IF NOT EXISTS user_settings (
  id text NOT NULL,
  user_id text NOT NULL,
  valor text NOT NULL,
  updated_at bigint NOT NULL,
  deleted_at bigint,
  server_updated_at bigint NOT NULL,
  PRIMARY KEY (user_id, id)
);
```

Keep the same connection/exit/logging pattern as the reference script (e.g. `console.log` on success, non-zero exit on error). Do NOT hardcode credentials — read `DATABASE_URL` from env like the reference.

- [ ] **Step 3: Lint the script**

Run: `npm run lint`
Expected: clean.

- [ ] **Step 4: Commit**

```bash
git add scripts/migrate-user-settings.mjs
git commit -m "chore(db): script DDL Neon para tabla user_settings"
```

> El script se EJECUTA en el momento del deploy (`node scripts/migrate-user-settings.mjs` con el `DATABASE_URL` de Neon), antes de subir el código que lee/escribe la tabla. No se ejecuta en esta fase de implementación.

---

## Task 9: Verificación final

- [ ] **Step 1: Full suite**

Run: `npx vitest run`
Expected: todo verde (incluye los nuevos: user-settings, use-setting, apply userSettings, backup userSettings; y los previos de A siguen verdes).

- [ ] **Step 2: Types + lint**

Run: `npx tsc --noEmit && npm run lint`
Expected: sin errores.

- [ ] **Step 3: Build**

Run: `npm run build`
Expected: build OK (usa `--webpack`).

---

## Self-Review (hecho)

**Cobertura del spec (módulo 0):**
- Entidad `UserSetting` id=clave + valor JSON → Task 1. ✓
- Dexie tabla `userSettings` → Task 1. ✓
- Drizzle `user_settings` PK compuesta `(user_id, id)` + upsert compuesto → Task 5. ✓
- Registrar en los 3 registros de sync (collect, apply, server-tables) → Tasks 5 (server-tables) + 6 (collect, apply). ✓
- Backup → Task 7. ✓
- Migración DDL Neon (DDL directo) → Task 8. ✓
- API cliente `getSetting`/`setSetting`/`deleteSetting` + `useSetting` → Tasks 1, 2. ✓
- Migración idempotente de A desde localStorage → Task 3. ✓
- `useModoProgresion`/`useIncrementos` pasan a `useSetting`; `useSuggestNextRoutine`/gym-filter siguen en localStorage → Task 4. ✓
- Tests: round-trip número/objeto, fallback, migración idempotente, convergencia LWW por clave, tombstone/reset → Tasks 1, 2, 3, 6. ✓

**Sin placeholders:** todo el código está completo en cada paso.

**Consistencia de tipos:** `UserSetting { id, userId: string|null, valor: string, updatedAt, deletedAt }` definido en Task 1 y usado igual en repo, sync (collect/apply), backup y schema servidor. `getSetting<T>`/`setSetting<T>` ↔ `useSetting<T>` ↔ `useModoProgresion`/`useIncrementos` encajan (claves `'modoProgresion'`, `'incrementos'`). El conflict-target compuesto solo aplica a `name === 'userSettings'`; la PK compuesta del schema lo respalda. ✓

**Riesgo señalado:** Task 4 toca código de A ya mergeado (los hooks). La firma `[value, setter]` se mantiene, así que la card y la página de Ajustes de A no cambian; la diferencia es que el valor inicial es el fallback hasta que Dexie resuelve (useLiveQuery), lo cual la card ya tolera.
