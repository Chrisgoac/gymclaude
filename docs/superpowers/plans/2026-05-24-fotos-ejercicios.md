# Fotos de ejercicios + lista mejorada — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Asociar una foto (en Cloudflare R2, sincronizada) a cualquier ejercicio y mejorar la lista (miniaturas + filtros), con miniaturas también al entrenar y en rutinas.

**Architecture:** Entidad sincronizada `ExercisePhoto` (mapea `exerciseId → {url,key}`) — funciona para catálogo y propios por igual, ya que la foto no vive en el registro `Exercise`. Subida vía ruta Next.js server-proxy autenticada con Clerk que sube a R2 con el SDK de S3 (credenciales solo en servidor); el cliente comprime la imagen antes. Todo aditivo (sin migraciones destructivas), así que cada commit queda verde.

**Tech Stack:** Next.js 16 (App Router, `--webpack`), React 19, Dexie/IndexedDB, Drizzle + Neon, Clerk, `@aws-sdk/client-s3` (R2), Vitest + RTL + fake-indexeddb. Estética Brutalist Iron.

**Spec:** `docs/superpowers/specs/2026-05-24-fotos-ejercicios-design.md`

**Prerrequisito (provisión del usuario, para subir fotos de verdad):** bucket R2 con acceso público + token S3, y env vars `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`, `R2_PUBLIC_URL` en `.env.local` y Vercel. **No** hace falta para tests/build/deploy del resto (la ruta devuelve 503 sin credenciales).

---

## File Structure

**Crear:**
- `lib/repositories/exercise-photos.ts` — CRUD de fotos (Dexie) + `exercise-photos.test.ts`.
- `lib/image/compress.ts` — compresión/redimensionado en cliente + `compress.test.ts`.
- `lib/r2/client.ts` — helpers R2 (S3 SDK) server-side.
- `app/api/exercise-photos/route.ts` — subir/borrar objeto en R2 + `route.test.ts`.
- `components/exercise-photo-picker.tsx` — UI de añadir/cambiar/quitar foto + `.test.tsx`.

**Modificar:**
- `lib/db/types.ts` — interface `ExercisePhoto`.
- `lib/db/database.ts` — Dexie v8 (tabla `exercisePhotos`).
- `db/schema.ts` — tabla `exercise_photos`.
- `lib/sync/{collect,apply,server-tables}.ts` — registrar `exercisePhotos`.
- `components/exercise-list.tsx` (+ test) — miniaturas + chips de filtro.
- `app/ejercicios/[id]/page.tsx` — montar `ExercisePhotoPicker`.
- `components/logged-exercise-card.tsx` — miniatura.
- `components/routine-day-exercise-row.tsx` — miniatura.
- `lib/repositories/backup.ts` (+ test) — incluir `exercisePhotos` (v6).
- `package.json` — dependencia `@aws-sdk/client-s3`.

---

## Task 1: Entidad ExercisePhoto (Dexie v8 + Drizzle + sync)

**Files:** Modify `lib/db/types.ts`, `lib/db/database.ts`, `db/schema.ts`, `lib/sync/collect.ts`, `lib/sync/apply.ts`, `lib/sync/server-tables.ts`; Test `lib/sync/collect.test.ts`.

- [ ] **Step 1: Tipo + tabla Dexie + índice**

En `lib/db/types.ts`, añade (tras `Exercise`):

```ts
export interface ExercisePhoto extends SyncMeta {
  userId: string | null;
  exerciseId: string;
  url: string;   // URL pública en R2
  key: string;   // object key en R2
}
```

En `lib/db/database.ts`:
1. Importa `ExercisePhoto` en el import de tipos.
2. Declara la tabla en la clase: `exercisePhotos!: Table<ExercisePhoto, string>;`
3. Añade `version(8)` al final del constructor:

```ts
    this.version(8).stores({
      exercisePhotos: 'id, exerciseId, deletedAt',
    });
```

En `db/schema.ts`, añade al final:

```ts
export const exercisePhotos = pgTable('exercise_photos', {
  ...sync,
  exerciseId: text('exercise_id').notNull(),
  url: text('url').notNull(),
  key: text('key').notNull(),
});
```

- [ ] **Step 2: Escribir el test de sync (falla)**

En `lib/sync/collect.test.ts`, añade `db.exercisePhotos.clear()` al `Promise.all` del `beforeEach`, y añade dentro del `describe('collectDirty', ...)`:

```ts
  it('sincroniza las fotos de ejercicio', async () => {
    await db.exercisePhotos.clear();
    await db.exercisePhotos.put({
      id: 'p1', userId: null, exerciseId: 'seed-press-banca',
      url: 'https://pub.r2.dev/p1.jpg', key: 'u/seed-press-banca/p1.jpg',
      updatedAt: Date.now(), deletedAt: null,
    });
    const changes = await collectDirty(0);
    expect(changes.find((c) => c.table === 'exercisePhotos')?.records).toHaveLength(1);
  });
```

- [ ] **Step 3: Run (must fail)**

Run: `npm run test -- collect.test.ts`
Expected: FAIL — `exercisePhotos` no está en `SYNCABLE_TABLES`.

- [ ] **Step 4: Registrar en los 3 registros de sync**

En `lib/sync/collect.ts`, añade al final de `SYNCABLE_TABLES`:
```ts
  { name: 'exercisePhotos', table: asSync(db.exercisePhotos) },
```
En `lib/sync/apply.ts`, añade a `TABLE_BY_NAME`:
```ts
  exercisePhotos: asSync(db.exercisePhotos),
```
En `lib/sync/server-tables.ts`, añade a `SERVER_TABLES`:
```ts
  exercisePhotos: schema.exercisePhotos,
```

- [ ] **Step 5: Run (must pass)**

Run: `npm run test -- collect.test.ts`
Expected: PASS

- [ ] **Step 6: Verify suite + types + lint**

Run: `npm run test && npx tsc --noEmit && npm run lint`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add lib/db/types.ts lib/db/database.ts db/schema.ts lib/sync/collect.ts lib/sync/apply.ts lib/sync/server-tables.ts lib/sync/collect.test.ts
git commit -m "feat(fotos): entidad ExercisePhoto (Dexie v8 + Drizzle + sync)"
```

(El `db:push` a Neon es aditivo y lo ejecuta el controlador en la Task 9.)

---

## Task 2: Repositorio exercise-photos

**Files:** Create `lib/repositories/exercise-photos.ts`; Test `lib/repositories/exercise-photos.test.ts`.

- [ ] **Step 1: Tests (fallan).** Crea `lib/repositories/exercise-photos.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { setPhoto, removePhoto, getPhoto, getPhotosMap } from '@/lib/repositories/exercise-photos';

beforeEach(async () => {
  await db.exercisePhotos.clear();
});

describe('repo exercise-photos', () => {
  it('setPhoto crea una foto para el ejercicio', async () => {
    const prev = await setPhoto('e1', { url: 'https://r2/a.jpg', key: 'k/a.jpg' });
    expect(prev).toBeUndefined();
    expect((await getPhoto('e1'))?.url).toBe('https://r2/a.jpg');
  });

  it('setPhoto reemplaza (no duplica) y devuelve el key anterior', async () => {
    await setPhoto('e1', { url: 'https://r2/a.jpg', key: 'k/a.jpg' });
    const prevKey = await setPhoto('e1', { url: 'https://r2/b.jpg', key: 'k/b.jpg' });
    expect(prevKey).toBe('k/a.jpg');
    const activas = (await db.exercisePhotos.toArray()).filter((p) => p.deletedAt === null);
    expect(activas).toHaveLength(1);
    expect((await getPhoto('e1'))?.url).toBe('https://r2/b.jpg');
  });

  it('removePhoto soft-delete y devuelve el key', async () => {
    await setPhoto('e1', { url: 'https://r2/a.jpg', key: 'k/a.jpg' });
    const key = await removePhoto('e1');
    expect(key).toBe('k/a.jpg');
    expect(await getPhoto('e1')).toBeUndefined();
  });

  it('getPhotosMap mapea solo las activas por exerciseId', async () => {
    await setPhoto('e1', { url: 'https://r2/a.jpg', key: 'k/a.jpg' });
    await setPhoto('e2', { url: 'https://r2/b.jpg', key: 'k/b.jpg' });
    await removePhoto('e2');
    const map = await getPhotosMap();
    expect(map.get('e1')?.url).toBe('https://r2/a.jpg');
    expect(map.has('e2')).toBe(false);
  });
});
```

- [ ] **Step 2: Run (must fail).** `npm run test -- exercise-photos.test.ts` → FAIL (módulo inexistente).

- [ ] **Step 3: Implementar.** Crea `lib/repositories/exercise-photos.ts`:

```ts
import { db } from '@/lib/db/database';
import type { ExercisePhoto } from '@/lib/db/types';

const now = () => Date.now();
const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

async function activaDe(exerciseId: string): Promise<ExercisePhoto | undefined> {
  const all = await db.exercisePhotos.where('exerciseId').equals(exerciseId).toArray();
  return activo(all)[0];
}

/** Crea o reemplaza la foto del ejercicio. Devuelve el `key` anterior si lo había (para borrarlo de R2). */
export async function setPhoto(exerciseId: string, input: { url: string; key: string }): Promise<string | undefined> {
  const existente = await activaDe(exerciseId);
  if (existente) {
    const prevKey = existente.key;
    await db.exercisePhotos.update(existente.id, { url: input.url, key: input.key, updatedAt: now() });
    return prevKey;
  }
  const foto: ExercisePhoto = {
    id: crypto.randomUUID(),
    userId: null,
    exerciseId,
    url: input.url,
    key: input.key,
    updatedAt: now(),
    deletedAt: null,
  };
  await db.exercisePhotos.put(foto);
  return undefined;
}

/** Borra (soft-delete) la foto del ejercicio. Devuelve el `key` para borrarlo de R2. */
export async function removePhoto(exerciseId: string): Promise<string | undefined> {
  const existente = await activaDe(exerciseId);
  if (!existente) return undefined;
  await db.exercisePhotos.update(existente.id, { deletedAt: now(), updatedAt: now() });
  return existente.key;
}

export function getPhoto(exerciseId: string): Promise<ExercisePhoto | undefined> {
  return activaDe(exerciseId);
}

export async function getPhotosMap(): Promise<Map<string, ExercisePhoto>> {
  const map = new Map<string, ExercisePhoto>();
  for (const p of activo(await db.exercisePhotos.toArray())) map.set(p.exerciseId, p);
  return map;
}
```

- [ ] **Step 4: Run (must pass).** `npm run test -- exercise-photos.test.ts` → PASS.

- [ ] **Step 5: Verify.** `npx tsc --noEmit && npm run lint` → clean.

- [ ] **Step 6: Commit.**

```bash
git add lib/repositories/exercise-photos.ts lib/repositories/exercise-photos.test.ts
git commit -m "feat(fotos): repositorio de fotos de ejercicio (set/remove/getPhotosMap)"
```

---

## Task 3: Compresión de imagen en cliente

**Files:** Create `lib/image/compress.ts`; Test `lib/image/compress.test.ts`.

- [ ] **Step 1: Test (falla).** Crea `lib/image/compress.test.ts` (testea la lógica pura de dimensiones; el `canvas` real no está en jsdom):

```ts
import { describe, it, expect } from 'vitest';
import { calcularDimensiones } from '@/lib/image/compress';

describe('calcularDimensiones', () => {
  it('no agranda imágenes pequeñas', () => {
    expect(calcularDimensiones(800, 600, 1024)).toEqual({ width: 800, height: 600 });
  });
  it('reduce el lado mayor a max manteniendo proporción (horizontal)', () => {
    expect(calcularDimensiones(2048, 1024, 1024)).toEqual({ width: 1024, height: 512 });
  });
  it('reduce el lado mayor a max manteniendo proporción (vertical)', () => {
    expect(calcularDimensiones(1000, 4000, 1024)).toEqual({ width: 256, height: 1024 });
  });
});
```

- [ ] **Step 2: Run (must fail).** `npm run test -- compress.test.ts` → FAIL.

- [ ] **Step 3: Implementar.** Crea `lib/image/compress.ts`:

```ts
export function calcularDimensiones(w: number, h: number, max: number): { width: number; height: number } {
  const lado = Math.max(w, h);
  if (lado <= max) return { width: w, height: h };
  const escala = max / lado;
  return { width: Math.round(w * escala), height: Math.round(h * escala) };
}

/** Redimensiona a máx 1024px el lado mayor y exporta JPEG ~0.8. Devuelve un Blob. */
export async function compressImage(file: File, max = 1024, calidad = 0.8): Promise<Blob> {
  const bitmap = await createImageBitmap(file);
  const { width, height } = calcularDimensiones(bitmap.width, bitmap.height, max);
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('No se pudo crear el contexto de canvas');
  ctx.drawImage(bitmap, 0, 0, width, height);
  bitmap.close?.();
  return await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('No se pudo comprimir la imagen'))),
      'image/jpeg',
      calidad,
    );
  });
}
```

- [ ] **Step 4: Run (must pass).** `npm run test -- compress.test.ts` → PASS.

- [ ] **Step 5: Verify.** `npx tsc --noEmit && npm run lint` → clean.

- [ ] **Step 6: Commit.**

```bash
git add lib/image/compress.ts lib/image/compress.test.ts
git commit -m "feat(fotos): compresión/redimensionado de imagen en cliente"
```

---

## Task 4: Cliente R2 + ruta de subida

**Files:** Modify `package.json`; Create `lib/r2/client.ts`, `app/api/exercise-photos/route.ts`; Test `app/api/exercise-photos/route.test.ts`.

- [ ] **Step 1: Instalar el SDK de S3**

Run: `npm install @aws-sdk/client-s3`
Expected: se añade a `dependencies` en `package.json`.

- [ ] **Step 2: Crear el cliente R2.** Crea `lib/r2/client.ts`:

```ts
import { S3Client, PutObjectCommand, DeleteObjectCommand } from '@aws-sdk/client-s3';

export function r2Configured(): boolean {
  return Boolean(
    process.env.R2_ACCOUNT_ID &&
      process.env.R2_ACCESS_KEY_ID &&
      process.env.R2_SECRET_ACCESS_KEY &&
      process.env.R2_BUCKET &&
      process.env.R2_PUBLIC_URL,
  );
}

let cached: S3Client | null = null;
function client(): S3Client {
  if (!cached) {
    cached = new S3Client({
      region: 'auto',
      endpoint: `https://${process.env.R2_ACCOUNT_ID}.r2.cloudflarestorage.com`,
      credentials: {
        accessKeyId: process.env.R2_ACCESS_KEY_ID!,
        secretAccessKey: process.env.R2_SECRET_ACCESS_KEY!,
      },
    });
  }
  return cached;
}

export async function putImage(key: string, body: Uint8Array, contentType: string): Promise<void> {
  await client().send(
    new PutObjectCommand({ Bucket: process.env.R2_BUCKET!, Key: key, Body: body, ContentType: contentType }),
  );
}

export async function deleteR2Object(key: string): Promise<void> {
  await client().send(new DeleteObjectCommand({ Bucket: process.env.R2_BUCKET!, Key: key }));
}

export function publicUrl(key: string): string {
  return `${process.env.R2_PUBLIC_URL}/${key}`;
}
```

- [ ] **Step 3: Escribir el test de la ruta (falla).** Crea `app/api/exercise-photos/route.test.ts`:

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

import { POST, DELETE } from '@/app/api/exercise-photos/route';
import { r2Configured } from '@/lib/r2/client';

beforeEach(() => auth.mockReset());

function reqConFoto(exerciseId: string): Request {
  const fd = new FormData();
  fd.append('exerciseId', exerciseId);
  fd.append('file', new Blob([new Uint8Array([1, 2, 3])], { type: 'image/jpeg' }), 'foto.jpg');
  return new Request('http://localhost/api/exercise-photos', { method: 'POST', body: fd });
}

describe('POST /api/exercise-photos', () => {
  it('401 sin sesión', async () => {
    auth.mockResolvedValue({ userId: null });
    const res = await POST(reqConFoto('e1'));
    expect(res.status).toBe(401);
  });

  it('sube y devuelve url+key con sesión', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await POST(reqConFoto('e1'));
    expect(res.status).toBe(200);
    const json = await res.json();
    expect(json.key).toContain('u1/e1/');
    expect(json.url).toBe(`https://pub.r2.dev/${json.key}`);
  });

  it('503 si R2 no está configurado', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    vi.mocked(r2Configured).mockReturnValueOnce(false);
    const res = await POST(reqConFoto('e1'));
    expect(res.status).toBe(503);
  });
});

describe('DELETE /api/exercise-photos', () => {
  it('401 sin sesión', async () => {
    auth.mockResolvedValue({ userId: null });
    const res = await DELETE(new Request('http://localhost/api/exercise-photos', {
      method: 'DELETE', body: JSON.stringify({ key: 'k' }),
    }));
    expect(res.status).toBe(401);
  });
});
```

- [ ] **Step 4: Run (must fail).** `npm run test -- "app/api/exercise-photos/route.test.ts"` → FAIL (ruta inexistente). (Si el filtro con corchetes da problemas, usa `npm run test` completo.)

- [ ] **Step 5: Implementar la ruta.** Crea `app/api/exercise-photos/route.ts`:

```ts
import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { r2Configured, putImage, deleteR2Object, publicUrl } from '@/lib/r2/client';

export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  if (!r2Configured()) return new NextResponse('R2 no configurado', { status: 503 });

  const form = await req.formData();
  const exerciseId = String(form.get('exerciseId') ?? '');
  const file = form.get('file');
  if (!exerciseId || !(file instanceof Blob)) {
    return new NextResponse('Petición inválida', { status: 400 });
  }
  const key = `${userId}/${exerciseId}/${crypto.randomUUID()}.jpg`;
  const bytes = new Uint8Array(await file.arrayBuffer());
  await putImage(key, bytes, 'image/jpeg');
  return NextResponse.json({ url: publicUrl(key), key });
}

export async function DELETE(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  if (!r2Configured()) return new NextResponse('R2 no configurado', { status: 503 });

  const { key } = (await req.json()) as { key?: string };
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

- [ ] **Step 6: Run (must pass).** `npm run test -- "app/api/exercise-photos/route.test.ts"` (o `npm run test`) → PASS.

- [ ] **Step 7: Verify.** `npx tsc --noEmit && npm run lint` → clean.

- [ ] **Step 8: Commit.**

```bash
git add package.json package-lock.json lib/r2/client.ts app/api/exercise-photos
git commit -m "feat(fotos): cliente R2 + ruta de subida/borrado autenticada"
```

---

## Task 5: Componente ExercisePhotoPicker + detalle de ejercicio

**Files:** Create `components/exercise-photo-picker.tsx`, `components/exercise-photo-picker.test.tsx`; Modify `app/ejercicios/[id]/page.tsx`.

- [ ] **Step 1: Test (falla).** Crea `components/exercise-photo-picker.test.tsx`:

```tsx
import { it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { db } from '@/lib/db/database';
import { ExercisePhotoPicker } from '@/components/exercise-photo-picker';

beforeEach(async () => {
  await db.exercisePhotos.clear();
});

it('muestra placeholder cuando no hay foto y el botón de añadir', async () => {
  render(<ExercisePhotoPicker exerciseId="e1" />);
  expect(await screen.findByText(/Añadir foto/i)).toBeInTheDocument();
});

it('muestra la foto existente y el botón de quitar', async () => {
  await db.exercisePhotos.put({
    id: 'p1', userId: null, exerciseId: 'e1', url: 'https://pub.r2.dev/x.jpg', key: 'u/e1/x.jpg',
    updatedAt: Date.now(), deletedAt: null,
  });
  render(<ExercisePhotoPicker exerciseId="e1" />);
  const img = await screen.findByRole('img');
  expect(img).toHaveAttribute('src', 'https://pub.r2.dev/x.jpg');
  expect(screen.getByText(/Quitar/i)).toBeInTheDocument();
});
```

- [ ] **Step 2: Run (must fail).** `npm run test -- exercise-photo-picker.test.tsx` → FAIL.

- [ ] **Step 3: Implementar.** Crea `components/exercise-photo-picker.tsx`:

```tsx
'use client';

import { useRef, useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { getPhoto, setPhoto, removePhoto } from '@/lib/repositories/exercise-photos';
import { compressImage } from '@/lib/image/compress';

export function ExercisePhotoPicker({ exerciseId }: { exerciseId: string }) {
  const foto = useLiveQuery(() => getPhoto(exerciseId), [exerciseId]);
  const inputRef = useRef<HTMLInputElement>(null);
  const [estado, setEstado] = useState('');

  async function onFile(file: File) {
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      setEstado('Necesitas conexión para subir fotos.');
      return;
    }
    setEstado('Subiendo…');
    try {
      const blob = await compressImage(file);
      const fd = new FormData();
      fd.append('exerciseId', exerciseId);
      fd.append('file', blob, 'foto.jpg');
      const res = await fetch('/api/exercise-photos', { method: 'POST', body: fd });
      if (!res.ok) throw new Error(String(res.status));
      const { url, key } = (await res.json()) as { url: string; key: string };
      const prevKey = await setPhoto(exerciseId, { url, key });
      if (prevKey) void borrarEnR2(prevKey);
      setEstado('');
    } catch {
      setEstado('No se pudo subir la foto.');
    }
  }

  async function quitar() {
    const key = await removePhoto(exerciseId);
    if (key) void borrarEnR2(key);
  }

  return (
    <div className="space-y-2">
      {foto ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={foto.url} alt="Foto del ejercicio" className="h-40 w-full border-2 border-foreground object-cover" />
      ) : (
        <div className="grid h-40 w-full place-items-center border-2 border-dashed border-foreground bg-card/50">
          <span className="label-mono text-[11px] text-muted-foreground">Sin foto</span>
        </div>
      )}
      <div className="flex items-center gap-3">
        <button
          className="label-mono border-2 border-foreground bg-secondary px-3 py-2 text-[11px] text-secondary-foreground brutal-shadow-sm transition-transform active:translate-x-[2px] active:translate-y-[2px]"
          onClick={() => inputRef.current?.click()}
        >
          {foto ? 'Cambiar foto' : 'Añadir foto'}
        </button>
        {foto && (
          <button className="label-mono text-[10px] text-muted-foreground hover:text-destructive" onClick={quitar}>
            Quitar
          </button>
        )}
        {estado && <span className="label-mono text-[10px] text-muted-foreground">{estado}</span>}
      </div>
      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) void onFile(f);
          e.target.value = '';
        }}
      />
    </div>
  );
}

async function borrarEnR2(key: string): Promise<void> {
  try {
    await fetch('/api/exercise-photos', {
      method: 'DELETE',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ key }),
    });
  } catch {
    // best-effort
  }
}
```

- [ ] **Step 4: Run (must pass).** `npm run test -- exercise-photo-picker.test.tsx` → PASS.

- [ ] **Step 5: Montar en el detalle de ejercicio.** En `app/ejercicios/[id]/page.tsx`:
1. Importa: `import { ExercisePhotoPicker } from '@/components/exercise-photo-picker';`
2. En la rama **read-only** (catálogo), añade `<ExercisePhotoPicker exerciseId={exercise.id} />` justo después del `<h1>{exercise.nombre}</h1>`.
3. En la rama **editable** (propio), añade `<ExercisePhotoPicker exerciseId={exercise.id} />` justo después del `<h1>Editar ejercicio</h1>` (antes del `<ExerciseForm .../>`).

- [ ] **Step 6: Verify.** `npx tsc --noEmit && npm run lint && npm run test -- exercise-photo-picker.test.tsx` → clean/green.

- [ ] **Step 7: Commit.**

```bash
git add components/exercise-photo-picker.tsx components/exercise-photo-picker.test.tsx app/ejercicios/[id]/page.tsx
git commit -m "feat(fotos): ExercisePhotoPicker + foto en el detalle de ejercicio"
```

---

## Task 6: Lista con miniaturas + chips de filtro

**Files:** Modify `components/exercise-list.tsx`; Test `components/exercise-list.test.tsx`.

- [ ] **Step 1: Añadir el test del filtro por chip (falla).** En `components/exercise-list.test.tsx`, añade este test dentro del `describe('ExerciseList', ...)`:

```ts
  it('filtra por chip de grupo muscular', async () => {
    render(<ExerciseList />);
    await screen.findByText('Press de banca');
    await userEvent.click(screen.getByRole('button', { name: 'Bíceps' }));
    await waitFor(() => {
      expect(screen.queryByText('Press de banca')).not.toBeInTheDocument();
      expect(screen.getByText('Curl martillo')).toBeInTheDocument();
    });
  });
```

(Los dos tests existentes — nombres/grupos y búsqueda de texto — deben seguir verdes.)

- [ ] **Step 2: Run (must fail).** `npm run test -- exercise-list.test.tsx` → FAIL (no hay chip "Bíceps" clicable).

- [ ] **Step 3: Implementar.** Reemplaza `components/exercise-list.tsx` por:

```tsx
'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import type { Exercise, MuscleGroup, Equipment } from '@/lib/db/types';
import { MUSCLE_GROUPS, EQUIPMENTS } from '@/lib/db/types';
import { listExercises } from '@/lib/repositories/exercises';
import { getPhotosMap } from '@/lib/repositories/exercise-photos';
import { muscleGroupLabel, equipmentLabel } from '@/lib/labels';
import { Input } from '@/components/ui/input';

export function ExerciseList() {
  const [query, setQuery] = useState('');
  const [grupo, setGrupo] = useState<MuscleGroup | null>(null);
  const [equipo, setEquipo] = useState<Equipment | null>(null);

  const exercises = useLiveQuery(() => listExercises(), []);
  const fotos = useLiveQuery(() => getPhotosMap(), []);

  // Grupos/equipos presentes (para los chips disponibles).
  const gruposPresentes = useMemo(() => {
    const s = new Set<MuscleGroup>();
    for (const e of exercises ?? []) s.add(e.grupoMuscular);
    return MUSCLE_GROUPS.filter((g) => s.has(g));
  }, [exercises]);

  const equiposPresentes = useMemo(() => {
    const s = new Set<Equipment>();
    for (const e of exercises ?? []) s.add(e.equipamiento);
    return EQUIPMENTS.filter((eq) => s.has(eq));
  }, [exercises]);

  const grouped = useMemo(() => {
    const list = (exercises ?? []).filter((e) => {
      if (!e.nombre.toLowerCase().includes(query.trim().toLowerCase())) return false;
      if (grupo && e.grupoMuscular !== grupo) return false;
      if (equipo && e.equipamiento !== equipo) return false;
      return true;
    });
    const map = new Map<MuscleGroup, Exercise[]>();
    for (const ex of list) {
      const arr = map.get(ex.grupoMuscular) ?? [];
      arr.push(ex);
      map.set(ex.grupoMuscular, arr);
    }
    return MUSCLE_GROUPS.filter((g) => map.has(g)).map((g) => ({ grupo: g, items: map.get(g)! }));
  }, [exercises, query, grupo, equipo]);

  if (exercises === undefined) return <p className="text-muted-foreground">Cargando…</p>;

  return (
    <div className="space-y-4">
      <Input placeholder="Buscar ejercicio…" value={query} onChange={(e) => setQuery(e.target.value)} />

      <div className="flex flex-wrap gap-2">
        {gruposPresentes.map((g) => {
          const activo = grupo === g;
          return (
            <button
              key={g}
              onClick={() => setGrupo(activo ? null : g)}
              className={`label-mono border-2 border-foreground px-2 py-1 text-[10px] ${
                activo ? 'bg-primary text-primary-foreground brutal-shadow-sm' : 'bg-card text-muted-foreground'
              }`}
            >
              {muscleGroupLabel[g]}
            </button>
          );
        })}
      </div>

      <div className="flex flex-wrap gap-2">
        {equiposPresentes.map((eq) => {
          const activo = equipo === eq;
          return (
            <button
              key={eq}
              onClick={() => setEquipo(activo ? null : eq)}
              className={`label-mono border-2 border-foreground px-2 py-1 text-[10px] ${
                activo ? 'bg-foreground text-background brutal-shadow-sm' : 'bg-card text-muted-foreground'
              }`}
            >
              {equipmentLabel[eq]}
            </button>
          );
        })}
      </div>

      {grouped.length === 0 && <p className="text-muted-foreground">No hay ejercicios.</p>}
      {grouped.map(({ grupo: g, items }) => (
        <section key={g} className="space-y-1">
          <h2 className="label-mono text-[11px] text-muted-foreground">{muscleGroupLabel[g]}</h2>
          <ul className="brutal-box divide-y-2 divide-foreground">
            {items.map((ex) => (
              <li key={ex.id}>
                <Link href={`/ejercicios/${ex.id}`} className="flex items-center gap-3 p-2">
                  {fotos?.get(ex.id) ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={fotos.get(ex.id)!.url} alt="" className="size-12 shrink-0 border-2 border-foreground object-cover" />
                  ) : (
                    <span className="size-12 shrink-0 border-2 border-foreground bg-card/50" aria-hidden="true" />
                  )}
                  <span className="flex-1 font-medium">{ex.nombre}</span>
                  <span className="label-mono text-[10px] text-muted-foreground">{equipmentLabel[ex.equipamiento]}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
```

- [ ] **Step 4: Run (must pass).** `npm run test -- exercise-list.test.tsx` → PASS (los 3 tests).

- [ ] **Step 5: Verify.** `npx tsc --noEmit && npm run lint` → clean (sin variables sin usar).

- [ ] **Step 6: Commit.**

```bash
git add components/exercise-list.tsx components/exercise-list.test.tsx
git commit -m "feat(fotos): lista de ejercicios con miniaturas + chips de filtro"
```

---

## Task 7: Miniatura al entrenar y en rutinas

**Files:** Modify `components/logged-exercise-card.tsx`, `components/routine-day-exercise-row.tsx`.

- [ ] **Step 1: Miniatura en la tarjeta de registro.** En `components/logged-exercise-card.tsx`:
1. Añade el import: `import { getPhoto } from '@/lib/repositories/exercise-photos';`
2. Dentro del componente, añade: `const foto = useLiveQuery(() => getPhoto(loggedExercise.exerciseId), [loggedExercise.exerciseId]);`
3. En la cabecera de la tarjeta (el `div` con `bg-foreground` que contiene el nombre), envuelve el nombre con una fila que incluya la miniatura a la izquierda. Sustituye el `<span>` del nombre por:

```tsx
        <span className="flex items-center gap-2">
          {foto && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={foto.url} alt="" className="size-7 shrink-0 border border-background object-cover" />
          )}
          <span className="font-[family-name:var(--font-display)] text-lg uppercase leading-none tracking-wide text-background">
            {ejercicio?.nombre ?? '—'}
          </span>
        </span>
```

(Mantén el botón "Quitar" tal cual a la derecha.)

- [ ] **Step 2: Miniatura en la fila del editor de rutina.** En `components/routine-day-exercise-row.tsx`:
1. Añade el import: `import { getPhoto } from '@/lib/repositories/exercise-photos';`
2. Añade: `const foto = useLiveQuery(() => getPhoto(routineExercise.exerciseId), [routineExercise.exerciseId]);`
3. En el `div` de la cabecera (el que tiene el nombre y el botón "Quitar"), sustituye el `<span>` del nombre por una fila con miniatura:

```tsx
        <span className="flex items-center gap-2">
          {foto && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={foto.url} alt="" className="size-8 shrink-0 border-2 border-foreground object-cover" />
          )}
          <span className="font-medium">{ejercicio?.nombre ?? '—'}</span>
        </span>
```

- [ ] **Step 3: Verify.** `npx tsc --noEmit && npm run lint && npm run test` → todo verde (los tests existentes de ambos componentes siguen pasando; no se asume foto).

- [ ] **Step 4: Commit.**

```bash
git add components/logged-exercise-card.tsx components/routine-day-exercise-row.tsx
git commit -m "feat(fotos): miniatura al entrenar y en el editor de rutina"
```

---

## Task 8: Backup incluye exercisePhotos (v6)

**Files:** Modify `lib/repositories/backup.ts`; Test `lib/repositories/backup.test.ts`.

- [ ] **Step 1: Test (falla).** En `lib/repositories/backup.test.ts`, añade `db.exercisePhotos.clear()` al `Promise.all` del `beforeEach`, y añade:

```ts
it('exporta e importa las fotos de ejercicio', async () => {
  await db.exercisePhotos.clear();
  await db.exercisePhotos.put({
    id: 'p1', userId: null, exerciseId: 'seed-press-banca',
    url: 'https://pub.r2.dev/x.jpg', key: 'u/e/x.jpg', updatedAt: Date.now(), deletedAt: null,
  });
  const backup = await exportData();
  expect(backup.data.exercisePhotos).toHaveLength(1);
  await db.exercisePhotos.clear();
  await importData(backup);
  expect(await db.exercisePhotos.count()).toBe(1);
});
```

- [ ] **Step 2: Run (must fail).** `npm run test -- backup.test.ts` → FAIL.

- [ ] **Step 3: Implementar.** En `lib/repositories/backup.ts`:
1. Añade `ExercisePhoto` al import de tipos.
2. Añade `exercisePhotos: ExercisePhoto[];` a `BackupFile.data`.
3. Cambia `version: 5` → `version: 6`.
4. Añade `exercisePhotos: await db.exercisePhotos.toArray(),` al objeto `data` de `exportData`.
5. En `importData`: añade `db.exercisePhotos` al array `tables` y, dentro de la transacción, `if (d.exercisePhotos?.length) await db.exercisePhotos.bulkPut(d.exercisePhotos);`.

- [ ] **Step 4: Run (must pass).** `npm run test -- backup.test.ts` → PASS.

- [ ] **Step 5: Verify.** `npx tsc --noEmit && npm run lint` → clean.

- [ ] **Step 6: Commit.**

```bash
git add lib/repositories/backup.ts lib/repositories/backup.test.ts
git commit -m "feat(fotos): incluir exercisePhotos en backup (v6)"
```

---

## Task 9: Verificación final + esquema en Neon

**Files:** ninguno (verificación + DDL).

- [ ] **Step 1: Suite + tipos + lint.**

Run: `npm run test && npx tsc --noEmit && npm run lint`
Expected: todo verde (88 previos + nuevos).

- [ ] **Step 2: Build de producción.**

Run: `npm run build`
Expected: `Compiled successfully`, SW de Serwist generado, ruta `/api/exercise-photos` listada.

- [ ] **Step 3: Aplicar el esquema a Neon (controlador).**

> Aditivo (tabla nueva `exercise_photos`), sin prompts y seguro para la app desplegada.

Run: `npm run db:push`
Expected: `Changes applied` (crea `exercise_photos`).

- [ ] **Step 4: Commit final (si quedara algo).**

```bash
git add -A
git commit -m "chore: verificación final fotos de ejercicios" || echo "nada que commitear"
```

---

## Notas de cierre

- **Provisión R2 + redeploy:** para que la subida de fotos funcione en vivo, el usuario crea el bucket R2 (acceso público) + token S3 y añade las 5 env vars (`R2_*`) en Vercel; luego `vercel --prod`. El `db:push` de la Task 9 es aditivo (no rompe la app desplegada vieja), así que no necesita acoplarse al deploy.
- **Service worker (caché de imágenes R2) DIFERIDO conscientemente:** añadir una regla de runtime caching para el host de R2 en `app/sw.ts` requiere conocer el host en build (env en el SW) y tocar la config de Serwist; se deja fuera para no arriesgar el build. Las fotos se cargan desde R2 por red; es la única desviación respecto al spec (que ya lo marcaba como mejora menor opcional).
- Estética Brutalist Iron en todos los componentes nuevos (miniaturas con borde 2px, placeholder cuadrado, chips como `gym-filter`).
- Build con `--webpack` (Serwist).
