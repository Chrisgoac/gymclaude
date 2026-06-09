# Fotos D2a — Fundación: entidad ProgressPhoto + ruta API (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entidad `ProgressPhoto` sincronizada (Dexie + Drizzle + sync + backup + DDL Neon) y la ruta `/api/progress-photos` (POST/DELETE), sin UI todavía.

**Architecture:** Mismo patrón de entidad sincronizada usado en D1a (BodyMetric) + el patrón de imágenes de `exercise-photos` (R2 + ruta). La entidad guarda url/key/fecha/angulo/nota; la ruta solo sube/borra el objeto en R2 (la metadata la guarda la entidad en el cliente).

**Tech Stack:** Dexie v14 · Drizzle/Neon · @aws-sdk/client-s3 (vía `lib/r2/client.ts`) · Clerk · vitest (proyectos `api` node + `app` jsdom).

**Nota:** Fase **D2a** del spec `docs/superpowers/specs/2026-06-09-fotos-progreso-design.md`. Estado actual confirmado: Dexie en v13, backup en versión 9. Reusa `lib/r2/client.ts` (`r2Configured`/`putImage`/`deleteR2Object`/`publicUrl`) y espeja `app/api/exercise-photos/route.ts` + su `route.test.ts`.

---

## File Structure

- **Modify** `lib/db/types.ts` — `AnguloFoto` + `ProgressPhoto`.
- **Modify** `lib/db/database.ts` — tabla `progressPhotos` + `this.version(14)`.
- **Create** `lib/repositories/progress-photos.ts` — `listPhotos`/`addPhoto`/`deletePhoto`.
- **Create** `lib/repositories/progress-photos.test.ts`.
- **Modify** `db/schema.ts` — tabla `progress_photos`.
- **Modify** `lib/sync/server-tables.ts` — registrar `progressPhotos`.
- **Modify** `lib/sync/collect.ts` + `lib/sync/apply.ts` (+ tests) — registrar `progressPhotos`.
- **Modify** `lib/repositories/backup.ts` (+ test) — incluir `progressPhotos`, versión 10.
- **Create** `scripts/migrate-progress-photos.mjs` — DDL Neon.
- **Create** `app/api/progress-photos/route.ts` + `app/api/progress-photos/route.test.ts`.

---

## Task 1: Entidad `ProgressPhoto` + Dexie v14 + repo

**Files:**
- Modify: `lib/db/types.ts`
- Modify: `lib/db/database.ts`
- Create: `lib/repositories/progress-photos.ts`
- Create: `lib/repositories/progress-photos.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/db/types.ts` (interface `SyncMeta`, `ExercisePhoto`, `BodyMetric`), `lib/db/database.ts` (la clase, `exercisePhotos!: Table<…>` línea ~16, y `this.version(13).stores({ bodyMetrics: 'id, tipo, fecha, deletedAt' })` línea ~122), `lib/repositories/exercise-photos.ts` y `lib/repositories/body.ts` (idioms `now`/`activo`).

- [ ] **Step 1: Add the entity** — in `lib/db/types.ts`, near `ExercisePhoto`:

```ts
export type AnguloFoto = 'frente' | 'lado' | 'espalda';

export interface ProgressPhoto extends SyncMeta {
  userId: string | null;
  url: string;
  key: string;
  fecha: number;
  angulo: AnguloFoto;
  nota: string | null;
}
```

- [ ] **Step 2: Add the Dexie table** — in `lib/db/database.ts`:

1. In the class field declarations (next to `exercisePhotos!`): add
```ts
  progressPhotos!: Table<ProgressPhoto, string>;
```
2. Add the import of `ProgressPhoto` to the existing `import type { … } from './types';`.
3. After the `this.version(13)...` block add:
```ts
    this.version(14).stores({
      progressPhotos: 'id, fecha, angulo, deletedAt',
    });
```

- [ ] **Step 3: Write the failing repo test** — create `lib/repositories/progress-photos.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { addPhoto, listPhotos, deletePhoto } from '@/lib/repositories/progress-photos';

beforeEach(async () => { await db.progressPhotos.clear(); });

describe('progress-photos repo', () => {
  it('addPhoto crea una foto con los campos dados', async () => {
    const f = await addPhoto({ url: 'u', key: 'k', fecha: 1000, angulo: 'frente', nota: 'hola' });
    expect(f.id).toBeTruthy();
    expect(f.deletedAt).toBeNull();
    const todas = await listPhotos();
    expect(todas).toHaveLength(1);
    expect(todas[0]).toMatchObject({ url: 'u', key: 'k', fecha: 1000, angulo: 'frente', nota: 'hola' });
  });

  it('listPhotos ordena por fecha desc y excluye tombstones', async () => {
    await addPhoto({ url: 'a', key: 'ka', fecha: 1000, angulo: 'frente', nota: null });
    const b = await addPhoto({ url: 'b', key: 'kb', fecha: 3000, angulo: 'lado', nota: null });
    await addPhoto({ url: 'c', key: 'kc', fecha: 2000, angulo: 'espalda', nota: null });
    await deletePhoto(b.id);
    const todas = await listPhotos();
    expect(todas.map((p) => p.fecha)).toEqual([2000, 1000]);
  });

  it('deletePhoto marca tombstone y devuelve el key', async () => {
    const f = await addPhoto({ url: 'u', key: 'mykey', fecha: 1, angulo: 'frente', nota: null });
    const key = await deletePhoto(f.id);
    expect(key).toBe('mykey');
    expect(await listPhotos()).toHaveLength(0);
  });
});
```

- [ ] **Step 4: Run → FAIL**

Run: `npx vitest run lib/repositories/progress-photos.test.ts` → FAIL (module not found).

- [ ] **Step 5: Implement the repo** — create `lib/repositories/progress-photos.ts`:

```ts
import { db } from '@/lib/db/database';
import type { ProgressPhoto, AnguloFoto } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

/** Fotos activas, orden fecha desc (más reciente primero). */
export async function listPhotos(): Promise<ProgressPhoto[]> {
  const all = activo(await db.progressPhotos.toArray());
  return all.sort((a, b) => b.fecha - a.fecha);
}

/** Crea una foto de progreso. La metadata se guarda aquí; el objeto ya está en R2. */
export async function addPhoto(input: {
  url: string;
  key: string;
  fecha: number;
  angulo: AnguloFoto;
  nota: string | null;
}): Promise<ProgressPhoto> {
  const ts = now();
  const foto: ProgressPhoto = {
    id: crypto.randomUUID(),
    userId: null,
    url: input.url,
    key: input.key,
    fecha: input.fecha,
    angulo: input.angulo,
    nota: input.nota,
    updatedAt: ts,
    deletedAt: null,
  };
  await db.progressPhotos.put(foto);
  return foto;
}

/** Borra (tombstone) una foto. Devuelve el `key` para limpiar el objeto en R2. */
export async function deletePhoto(id: string): Promise<string | undefined> {
  const foto = await db.progressPhotos.get(id);
  if (!foto) return undefined;
  const ts = now();
  await db.progressPhotos.update(id, { deletedAt: ts, updatedAt: ts });
  return foto.key;
}
```

- [ ] **Step 6: Run → PASS** + `npx tsc --noEmit` clean.

- [ ] **Step 7: Commit**

```bash
git add lib/db/types.ts lib/db/database.ts lib/repositories/progress-photos.ts lib/repositories/progress-photos.test.ts
git commit -m "feat(fotos): entidad ProgressPhoto + tabla Dexie v14 + repo"
```

---

## Task 2: Tabla servidor `progress_photos` + SERVER_TABLES

**Files:**
- Modify: `db/schema.ts`
- Modify: `lib/sync/server-tables.ts`

- [ ] **Step 0: READ FIRST**

Read `db/schema.ts` (helper `sync` spread + tabla `exercisePhotos` líneas ~75-80 + `bodyMetrics`), `lib/sync/server-tables.ts` (`SERVER_TABLES`).

- [ ] **Step 1: Add the Drizzle table** — in `db/schema.ts`, after `bodyMetrics`:

```ts
export const progressPhotos = pgTable('progress_photos', {
  ...sync,
  url: text('url').notNull(),
  key: text('key').notNull(),
  fecha: bigint('fecha', { mode: 'number' }).notNull(),
  angulo: text('angulo').notNull(),
  nota: text('nota'),
});
```
(Confirm `bigint`/`text` are already imported in this file — they are, used by `bodyMetrics`/`exercisePhotos`.)

- [ ] **Step 2: Register in SERVER_TABLES** — in `lib/sync/server-tables.ts` add:
```ts
  progressPhotos: schema.progressPhotos,
```

- [ ] **Step 3: Typecheck** — `npx tsc --noEmit` clean.

- [ ] **Step 4: Commit**

```bash
git add db/schema.ts lib/sync/server-tables.ts
git commit -m "feat(fotos): tabla progress_photos (servidor) + SERVER_TABLES"
```

---

## Task 3: Registrar `progressPhotos` en collect/apply

**Files:**
- Modify: `lib/sync/collect.ts`
- Modify: `lib/sync/apply.ts`
- Modify: `lib/sync/apply.test.ts`
- Modify: `lib/sync/collect.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/sync/collect.ts` (`SYNCABLE_TABLES`, `asSync`), `lib/sync/apply.ts` (`TABLE_BY_NAME`), `lib/sync/apply.test.ts` + `lib/sync/collect.test.ts` (the `bodyMetrics` test idioms with `as unknown as SyncMeta[]`).

- [ ] **Step 1: Write failing tests.**

Append to `lib/sync/apply.test.ts`:
```ts
it('progressPhotos se aplican por id (LWW)', async () => {
  await db.progressPhotos.clear();
  await applyIncoming([{ table: 'progressPhotos', records: [
    { id: 'pp1', userId: 'u1', url: 'u', key: 'k', fecha: 100, angulo: 'frente', nota: null, updatedAt: 100, deletedAt: null },
  ] as unknown as import('@/lib/db/types').SyncMeta[] }]);
  const p = await db.progressPhotos.get('pp1');
  expect(p?.key).toBe('k');
  expect(await db.progressPhotos.count()).toBe(1);
});
```
Append to `lib/sync/collect.test.ts`:
```ts
it('sincroniza progressPhotos', async () => {
  await db.progressPhotos.clear();
  await db.progressPhotos.put({ id: 'pp-c1', userId: null, url: 'u', key: 'k', fecha: 1, angulo: 'lado', nota: null, updatedAt: 1000, deletedAt: null });
  const changes = await collectDirty(0);
  expect(changes.find((c) => c.table === 'progressPhotos')?.records).toHaveLength(1);
});
```
(Match the real imported function names in each test file.)

- [ ] **Step 2: Run → FAIL** (`npx vitest run lib/sync/`).

- [ ] **Step 3:** In `lib/sync/apply.ts` add to `TABLE_BY_NAME`: `progressPhotos: asSync(db.progressPhotos),`
- [ ] **Step 4:** In `lib/sync/collect.ts` add to `SYNCABLE_TABLES` (no `shouldSync`): `{ name: 'progressPhotos', table: asSync(db.progressPhotos) },`

- [ ] **Step 5: Run → PASS** (`npx vitest run lib/sync/`) + `npx tsc --noEmit` clean.

- [ ] **Step 6: Commit**

```bash
git add lib/sync/collect.ts lib/sync/apply.ts lib/sync/apply.test.ts lib/sync/collect.test.ts
git commit -m "feat(sync): progressPhotos en collect/apply"
```

---

## Task 4: Backup `progressPhotos` (versión 10)

**Files:**
- Modify: `lib/repositories/backup.ts`
- Modify: `lib/repositories/backup.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/backup.ts` (current `version: 9`; `bodyMetrics` added everywhere) y `lib/repositories/backup.test.ts`.

- [ ] **Step 1: Write the failing test** — append to `lib/repositories/backup.test.ts`:

```ts
it('exporta e importa progressPhotos', async () => {
  await db.progressPhotos.clear();
  await db.progressPhotos.put({ id: 'bk1', userId: null, url: 'u', key: 'k', fecha: 5, angulo: 'frente', nota: 'x', updatedAt: 5, deletedAt: null });
  const backup = await exportData();
  expect(backup.data.progressPhotos).toHaveLength(1);
  await db.progressPhotos.clear();
  await importData(backup);
  expect((await db.progressPhotos.get('bk1'))?.key).toBe('k');
});
```

- [ ] **Step 2: Run → FAIL** (`backup.data.progressPhotos` undefined).

- [ ] **Step 3: Implement** — in `lib/repositories/backup.ts`:
1. Add `ProgressPhoto` to the `import type { … } from '@/lib/db/types';`.
2. In `BackupFile.data`, add: `progressPhotos: ProgressPhoto[];`
3. Bump `version: 9` → `version: 10` in `exportData`.
4. In `exportData` data: `progressPhotos: await db.progressPhotos.toArray(),`
5. In `importData`: add `db.progressPhotos` to the `tables` tuple, and inside the transaction `if (d.progressPhotos?.length) await db.progressPhotos.bulkPut(d.progressPhotos);`
6. If a hand-rolled `BackupFile` fixture in the tests fails tsc for the missing field, add `progressPhotos: []` to it.

- [ ] **Step 4: Run → PASS** (`npx vitest run lib/repositories/backup.test.ts`) + `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/backup.ts lib/repositories/backup.test.ts
git commit -m "feat(backup): incluir progressPhotos (version 10)"
```

---

## Task 5: Script DDL Neon `progress_photos`

**Files:**
- Create: `scripts/migrate-progress-photos.mjs`

- [ ] **Step 0: READ FIRST**

Read `scripts/migrate-body-metrics.mjs` (env loader, conexión Neon, `CREATE TABLE IF NOT EXISTS`, verificación de columnas, salida) y la tabla `progress_photos` recién creada en `db/schema.ts` para casar columnas/tipos.

- [ ] **Step 1: Create `scripts/migrate-progress-photos.mjs`**

Mirror `migrate-body-metrics.mjs` exactly, but for table `progress_photos`. DDL (columnas sync idénticas a las del script de body-metrics + las específicas):
```sql
CREATE TABLE IF NOT EXISTS progress_photos (
  id text PRIMARY KEY,
  user_id text NOT NULL,
  updated_at bigint NOT NULL,
  deleted_at bigint,
  server_updated_at bigint NOT NULL,
  url text NOT NULL,
  key text NOT NULL,
  fecha bigint NOT NULL,
  angulo text NOT NULL,
  nota text
)
```
Misma carga de `.env.local`, misma `DATABASE_URL_UNPOOLED || DATABASE_URL`, misma verificación post-create (`information_schema.columns`).

- [ ] **Step 2: Syntax-check only** — `node --check scripts/migrate-progress-photos.mjs` (NO ejecutar contra Neon; lo corre el usuario/controlador en deploy).

- [ ] **Step 3: Commit**

```bash
git add scripts/migrate-progress-photos.mjs
git commit -m "chore(db): script DDL Neon para tabla progress_photos"
```

---

## Task 6: Ruta `/api/progress-photos` (POST/DELETE)

**Files:**
- Create: `app/api/progress-photos/route.ts`
- Create: `app/api/progress-photos/route.test.ts`

- [ ] **Step 0: READ FIRST**

Read `app/api/exercise-photos/route.ts` y `app/api/exercise-photos/route.test.ts` (los espejas). Diferencia clave: NO hay `exerciseId`; el key es `` `${userId}/progress/${crypto.randomUUID()}.jpg` ``; el form solo trae `file`.

- [ ] **Step 1: Write the failing tests** — create `app/api/progress-photos/route.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest';

const auth = vi.fn();
vi.mock('@clerk/nextjs/server', () => ({ auth: () => auth() }));
vi.mock('@/lib/r2/client', () => ({
  r2Configured: vi.fn(() => true),
  putImage: vi.fn().mockResolvedValue(undefined),
  deleteR2Object: vi.fn().mockResolvedValue(undefined),
  publicUrl: (key: string) => `https://pub.r2.dev/${key}`,
}));

import { POST, DELETE } from '@/app/api/progress-photos/route';
import { r2Configured } from '@/lib/r2/client';

beforeEach(() => auth.mockReset());

function reqConFoto(): Request {
  const fd = new FormData();
  fd.append('file', new Blob([new Uint8Array([1, 2, 3])], { type: 'image/jpeg' }), 'foto.jpg');
  return new Request('http://localhost/api/progress-photos', { method: 'POST', body: fd });
}

describe('POST /api/progress-photos', () => {
  it('401 sin sesión', async () => {
    auth.mockResolvedValue({ userId: null });
    expect((await POST(reqConFoto())).status).toBe(401);
  });
  it('sube y devuelve url+key namespaced en /progress/', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await POST(reqConFoto());
    expect(res.status).toBe(200);
    const json = await res.json();
    expect(json.key).toContain('u1/progress/');
    expect(json.url).toBe(`https://pub.r2.dev/${json.key}`);
  });
  it('503 si R2 no está configurado', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    vi.mocked(r2Configured).mockReturnValueOnce(false);
    expect((await POST(reqConFoto())).status).toBe(503);
  });
  it('400 si el tipo no es imagen', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const fd = new FormData();
    fd.append('file', new Blob([new Uint8Array([1])], { type: 'text/plain' }), 'x.txt');
    const res = await POST(new Request('http://localhost/api/progress-photos', { method: 'POST', body: fd }));
    expect(res.status).toBe(400);
  });
  it('413 si la imagen es demasiado grande', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const fd = new FormData();
    fd.append('file', new Blob([new Uint8Array(9 * 1024 * 1024)], { type: 'image/jpeg' }), 'foto.jpg');
    const res = await POST(new Request('http://localhost/api/progress-photos', { method: 'POST', body: fd }));
    expect(res.status).toBe(413);
  });
});

describe('DELETE /api/progress-photos', () => {
  it('401 sin sesión', async () => {
    auth.mockResolvedValue({ userId: null });
    const res = await DELETE(new Request('http://localhost/api/progress-photos', { method: 'DELETE', body: JSON.stringify({ key: 'k' }) }));
    expect(res.status).toBe(401);
  });
  it('403 si el key es de otro usuario', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await DELETE(new Request('http://localhost/api/progress-photos', { method: 'DELETE', body: JSON.stringify({ key: 'otro/progress/x.jpg' }) }));
    expect(res.status).toBe(403);
  });
  it('borra OK un key propio', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await DELETE(new Request('http://localhost/api/progress-photos', { method: 'DELETE', body: JSON.stringify({ key: 'u1/progress/x.jpg' }) }));
    expect(res.status).toBe(200);
  });
});
```

- [ ] **Step 2: Run → FAIL** (`npx vitest run app/api/progress-photos/route.test.ts`).

- [ ] **Step 3: Implement** — create `app/api/progress-photos/route.ts`:

```ts
import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { r2Configured, putImage, deleteR2Object, publicUrl } from '@/lib/r2/client';

export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  if (!r2Configured()) return new NextResponse('R2 no configurado', { status: 503 });

  const form = await req.formData();
  const file = form.get('file');
  if (!(file instanceof Blob)) {
    return new NextResponse('Petición inválida', { status: 400 });
  }
  // La ruta es la frontera de confianza: validar tipo y tamaño aunque el cliente ya comprima.
  if (file.type && !file.type.startsWith('image/')) {
    return new NextResponse('Tipo no permitido', { status: 400 });
  }
  if (file.size > 8 * 1024 * 1024) {
    return new NextResponse('Imagen demasiado grande', { status: 413 });
  }
  const key = `${userId}/progress/${crypto.randomUUID()}.jpg`;
  const bytes = new Uint8Array(await file.arrayBuffer());
  await putImage(key, bytes, 'image/jpeg');
  return NextResponse.json({ url: publicUrl(key), key });
}

export async function DELETE(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  if (!r2Configured()) return new NextResponse('R2 no configurado', { status: 503 });

  const { key } = (await req.json()) as { key?: string };
  // El key está namespaced por userId; no permitir borrar objetos de otros usuarios.
  if (key && !key.startsWith(`${userId}/`)) {
    return new NextResponse('Forbidden', { status: 403 });
  }
  if (key) {
    try {
      await deleteR2Object(key);
    } catch {
      // best-effort: ignorar si el objeto no existe
    }
  }
  return NextResponse.json({ ok: true });
}
```

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit` + `npx eslint app/api/progress-photos/route.ts`.

- [ ] **Step 5: Commit**

```bash
git add app/api/progress-photos/route.ts app/api/progress-photos/route.test.ts
git commit -m "feat(fotos): ruta /api/progress-photos (POST/DELETE)"
```

---

## Task 7: Verificación final

- [ ] **Step 1:** `npx vitest run && npx tsc --noEmit && npm run lint && npm run build`
Expected: todo verde; ruta `/api/progress-photos` presente en el build.

---

## Self-Review (hecho)

- **Spec cobertura (D2a):** entidad `ProgressPhoto` (url/key/fecha/angulo/nota) ✓; Dexie v14 ✓; Drizzle `progress_photos` con tipos correctos (nota nullable) ✓; sync en los 3 registros sin `shouldSync` ✓; backup versión 10 ✓; DDL `scripts/migrate-progress-photos.mjs` idempotente ✓; ruta POST (401/503/400/413, key `${userId}/progress/${uuid}.jpg`) + DELETE (401/503/403 anti-IDOR/OK) ✓.
- **Tipos consistentes:** `AnguloFoto` reutilizado en types/repo; `addPhoto` input ↔ entidad; repo `now`/`activo` como en `body.ts`/`exercise-photos.ts`; ruta espeja exercise-photos (sin `exerciseId`).
- **Sin placeholders:** todo el código presente.
- **Orden de tareas:** entity → server table → collect/apply → backup → DDL → route → verify (mismo orden probado en D1a).
