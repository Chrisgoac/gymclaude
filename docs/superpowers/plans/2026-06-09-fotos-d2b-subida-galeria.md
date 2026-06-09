# Fotos D2b — Subida + galería (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Subir fotos de progreso (comprimir → R2 → entidad sincronizada) y mostrarlas en una galería agrupada por ángulo con miniaturas difuminadas que se revelan al tocar y borrado. Integrado como sección en `/cuerpo`.

**Architecture:** Constante pura de ángulos (`lib/progress-photos.ts`) + componente de subida (`progress-photo-upload`) + galería presentacional dirigida por props (`progress-gallery`) + una sección (`progress-photos-section`) que hace el único `useLiveQuery(listPhotos)` y compone ambos. Reusa el flujo de `components/exercise-photo-picker.tsx` (compressImage → POST → entidad; DELETE best-effort a R2).

**Tech Stack:** Next.js 16 (client) · Dexie `useLiveQuery` · `lib/image/compress.ts` · R2 vía `/api/progress-photos` (D2a) · Tailwind "Brutalist Iron" · vitest (proyecto `app` jsdom).

**Nota:** Fase **D2b** del spec `docs/superpowers/specs/2026-06-09-fotos-progreso-design.md`. D2a (entidad `ProgressPhoto`, repo `listPhotos`/`addPhoto`/`deletePhoto`, ruta `/api/progress-photos`) ya mergeada. La comparación lado a lado es D2c (no aquí).

**Repo D2a disponible** (`lib/repositories/progress-photos.ts`): `listPhotos()` (activas, fecha desc), `addPhoto({url,key,fecha,angulo,nota})`, `deletePhoto(id)` → devuelve key. Tipos `ProgressPhoto`, `AnguloFoto = 'frente'|'lado'|'espalda'` en `lib/db/types.ts`.

---

## File Structure

- **Create** `lib/progress-photos.ts` — `ANGULOS` (orden) + `anguloLabel`.
- **Create** `lib/progress-photos.test.ts`.
- **Create** `components/progress-photo-upload.tsx` — formulario de subida.
- **Create** `components/progress-photo-upload.test.tsx`.
- **Create** `components/progress-gallery.tsx` — galería (presentacional) con blur reveal + borrado.
- **Create** `components/progress-gallery.test.tsx`.
- **Create** `components/progress-photos-section.tsx` — compone subida + galería (useLiveQuery).
- **Modify** `app/cuerpo/page.tsx` — añade la sección.

---

## Task 1: Catálogo de ángulos (puro)

**Files:**
- Create: `lib/progress-photos.ts`
- Create: `lib/progress-photos.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/db/types.ts` (`AnguloFoto`). El orden de presentación es Frente → Lado → Espalda.

- [ ] **Step 1: Write the failing test** — create `lib/progress-photos.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { ANGULOS, anguloLabel } from '@/lib/progress-photos';

describe('catálogo de ángulos', () => {
  it('ANGULOS en orden frente, lado, espalda', () => {
    expect(ANGULOS).toEqual(['frente', 'lado', 'espalda']);
  });
  it('anguloLabel capitaliza cada ángulo', () => {
    expect(anguloLabel.frente).toBe('Frente');
    expect(anguloLabel.lado).toBe('Lado');
    expect(anguloLabel.espalda).toBe('Espalda');
  });
});
```

- [ ] **Step 2: Run → FAIL** (`npx vitest run lib/progress-photos.test.ts`).

- [ ] **Step 3: Implement** — create `lib/progress-photos.ts`:

```ts
import type { AnguloFoto } from '@/lib/db/types';

/** Ángulos en orden de presentación. */
export const ANGULOS: readonly AnguloFoto[] = ['frente', 'lado', 'espalda'];

export const anguloLabel: Record<AnguloFoto, string> = {
  frente: 'Frente',
  lado: 'Lado',
  espalda: 'Espalda',
};
```

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add lib/progress-photos.ts lib/progress-photos.test.ts
git commit -m "feat(fotos): catálogo de ángulos (ANGULOS + anguloLabel)"
```

---

## Task 2: Componente de subida

**Files:**
- Create: `components/progress-photo-upload.tsx`
- Create: `components/progress-photo-upload.test.tsx`

> Cliente. Selector de ángulo + fecha (hoy por defecto) + nota opcional + input de archivo (`accept="image/*"`, `capture="environment"`). Al elegir archivo: `compressImage` → `POST /api/progress-photos` → `{url,key}` → `addPhoto({url,key,fecha,angulo,nota})`. Estados inline.

- [ ] **Step 0: READ FIRST**

Read `components/exercise-photo-picker.tsx` (flujo `onFile`: navigator.onLine guard, `compressImage`, FormData, `fetch('/api/...')`, `!res.ok` → throw, estados "Subiendo…"/error; input file oculto disparado por botón), `lib/repositories/progress-photos.ts` (`addPhoto`), `lib/progress-photos.ts` (`ANGULOS`/`anguloLabel`), `components/body-form.tsx` (`hoyISO`, conversión fecha → epoch, clases brutalist de select/input/Button), `components/coach-chat.test.tsx` (idiom de mocks).

- [ ] **Step 1: Write the failing test** — create `components/progress-photo-upload.test.tsx`:

```tsx
import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const addPhoto = vi.fn().mockResolvedValue(undefined);
vi.mock('@/lib/repositories/progress-photos', () => ({ addPhoto: (...a: unknown[]) => addPhoto(...a) }));
vi.mock('@/lib/image/compress', () => ({ compressImage: vi.fn().mockResolvedValue(new Blob([new Uint8Array([1])], { type: 'image/jpeg' })) }));

import { ProgressPhotoUpload } from '@/components/progress-photo-upload';

beforeEach(() => {
  addPhoto.mockClear();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ url: 'https://r2/x.jpg', key: 'u1/progress/x.jpg' }) }));
  // online por defecto
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
});

function fakeFile() {
  return new File([new Uint8Array([1, 2, 3])], 'foto.jpg', { type: 'image/jpeg' });
}

it('al subir: comprime, postea y guarda la entidad con angulo/nota', async () => {
  render(<ProgressPhotoUpload />);
  await userEvent.selectOptions(screen.getByLabelText(/ángulo/i), 'lado');
  await userEvent.type(screen.getByLabelText(/nota/i), 'semana 1');
  const input = screen.getByLabelText(/foto/i, { selector: 'input[type="file"]' });
  await userEvent.upload(input, fakeFile());
  // espera microtareas del onFile async
  expect(fetch).toHaveBeenCalledWith('/api/progress-photos', expect.objectContaining({ method: 'POST' }));
  expect(addPhoto).toHaveBeenCalledTimes(1);
  const arg = addPhoto.mock.calls[0][0];
  expect(arg).toMatchObject({ url: 'https://r2/x.jpg', key: 'u1/progress/x.jpg', angulo: 'lado', nota: 'semana 1' });
  expect(typeof arg.fecha).toBe('number');
});

it('si la subida falla (res no ok), no guarda la entidad y muestra error', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503, json: async () => ({}) }));
  render(<ProgressPhotoUpload />);
  const input = screen.getByLabelText(/foto/i, { selector: 'input[type="file"]' });
  await userEvent.upload(input, fakeFile());
  expect(addPhoto).not.toHaveBeenCalled();
  expect(screen.getByText(/no se pudo subir/i)).toBeInTheDocument();
});
```

If `getByLabelText(/foto/i, { selector: … })` proves brittle for a hidden file input, expose the input with an accessible name via `aria-label="Foto"` and target it with `screen.getByLabelText('Foto')`. Keep the test asserting the same behavior.

- [ ] **Step 2: Run → FAIL** (`npx vitest run components/progress-photo-upload.test.tsx`).

- [ ] **Step 3: Implement** — create `components/progress-photo-upload.tsx`:

```tsx
'use client';

import { useRef, useState } from 'react';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { compressImage } from '@/lib/image/compress';
import { addPhoto } from '@/lib/repositories/progress-photos';
import { ANGULOS, anguloLabel } from '@/lib/progress-photos';
import type { AnguloFoto } from '@/lib/db/types';

function hoyISO(): string {
  const d = new Date();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

export function ProgressPhotoUpload() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [angulo, setAngulo] = useState<AnguloFoto>('frente');
  const [fecha, setFecha] = useState(hoyISO());
  const [nota, setNota] = useState('');
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
      fd.append('file', blob, 'foto.jpg');
      const res = await fetch('/api/progress-photos', { method: 'POST', body: fd });
      if (!res.ok) throw new Error(String(res.status));
      const { url, key } = (await res.json()) as { url: string; key: string };
      const fechaMs = new Date(`${fecha}T00:00:00`).getTime();
      await addPhoto({ url, key, fecha: fechaMs, angulo, nota: nota.trim() || null });
      setNota('');
      setEstado('');
    } catch {
      setEstado('No se pudo subir la foto.');
    }
  }

  return (
    <div className="brutal-box space-y-3 p-3">
      <p className="label-mono text-[10px] text-muted-foreground">Nueva foto de progreso</p>
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1">
          <Label htmlFor="angulo">Ángulo</Label>
          <select
            id="angulo"
            className="w-full border-2 border-foreground bg-card p-2"
            value={angulo}
            onChange={(e) => setAngulo(e.target.value as AnguloFoto)}
          >
            {ANGULOS.map((a) => (
              <option key={a} value={a}>
                {anguloLabel[a]}
              </option>
            ))}
          </select>
        </div>
        <div className="space-y-1">
          <Label htmlFor="fecha-foto">Fecha</Label>
          <input
            id="fecha-foto"
            type="date"
            className="h-9 w-full border-2 border-foreground bg-card px-2"
            value={fecha}
            onChange={(e) => setFecha(e.target.value)}
          />
        </div>
      </div>
      <div className="space-y-1">
        <Label htmlFor="nota-foto">Nota</Label>
        <Input id="nota-foto" value={nota} onChange={(e) => setNota(e.target.value)} placeholder="Opcional" />
      </div>
      <div className="flex items-center gap-3">
        <button
          type="button"
          className="label-mono border-2 border-foreground bg-secondary px-3 py-2 text-[11px] text-secondary-foreground brutal-shadow-sm transition-transform active:translate-x-[2px] active:translate-y-[2px]"
          onClick={() => inputRef.current?.click()}
        >
          Añadir foto
        </button>
        {estado && <span className="label-mono text-[10px] text-muted-foreground">{estado}</span>}
      </div>
      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        capture="environment"
        aria-label="Foto"
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
```

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit` + `npx eslint components/progress-photo-upload.tsx`.

- [ ] **Step 5: Commit**

```bash
git add components/progress-photo-upload.tsx components/progress-photo-upload.test.tsx
git commit -m "feat(fotos): componente de subida (compress → POST → addPhoto)"
```

---

## Task 3: Galería (presentacional, blur reveal + borrado)

**Files:**
- Create: `components/progress-gallery.tsx`
- Create: `components/progress-gallery.test.tsx`

> Presentacional: recibe `fotos: ProgressPhoto[]` (activas). Agrupa por ángulo en orden `ANGULOS`; secciones solo para ángulos con datos; dentro, miniaturas orden fecha desc. Miniatura difuminada (`blur`) por defecto; al tocar se revela (toggle por id). Borrar → `deletePhoto(id)` + DELETE best-effort a R2.

- [ ] **Step 0: READ FIRST**

Read `components/exercise-photo-picker.tsx` (helper `borrarEnR2` + `<img>` con `// eslint-disable-next-line @next/next/no-img-element`), `lib/repositories/progress-photos.ts` (`deletePhoto` devuelve key), `lib/progress-photos.ts`, `components/body-metric-card.tsx` (idiom de lista con borrar `✕`), `components/coach-chat.test.tsx` (mocks).

- [ ] **Step 1: Write the failing test** — create `components/progress-gallery.test.tsx`:

```tsx
import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ProgressPhoto } from '@/lib/db/types';

const deletePhoto = vi.fn().mockResolvedValue('u1/progress/x.jpg');
vi.mock('@/lib/repositories/progress-photos', () => ({ deletePhoto: (...a: unknown[]) => deletePhoto(...a) }));

import { ProgressGallery } from '@/components/progress-gallery';

const foto = (id: string, angulo: ProgressPhoto['angulo'], fecha: number): ProgressPhoto => ({
  id, userId: null, url: `https://r2/${id}.jpg`, key: `u1/progress/${id}.jpg`, fecha, angulo, nota: null, updatedAt: fecha, deletedAt: null,
});

beforeEach(() => {
  deletePhoto.mockClear();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ok: true }) }));
});

it('agrupa por ángulo: solo muestra secciones con datos', () => {
  render(<ProgressGallery fotos={[foto('a', 'frente', 1000), foto('b', 'lado', 2000)]} />);
  expect(screen.getByText('Frente')).toBeInTheDocument();
  expect(screen.getByText('Lado')).toBeInTheDocument();
  expect(screen.queryByText('Espalda')).not.toBeInTheDocument();
});

it('la miniatura está difuminada por defecto y se revela al tocar', async () => {
  render(<ProgressGallery fotos={[foto('a', 'frente', 1000)]} />);
  const img = screen.getByAltText(/frente/i);
  expect(img.className).toContain('blur');
  await userEvent.click(img);
  expect(img.className).not.toContain('blur');
});

it('borrar llama a deletePhoto con el id', async () => {
  render(<ProgressGallery fotos={[foto('a', 'frente', 1000)]} />);
  await userEvent.click(screen.getByRole('button', { name: /eliminar/i }));
  expect(deletePhoto).toHaveBeenCalledWith('a');
});
```

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement** — create `components/progress-gallery.tsx`:

```tsx
'use client';

import { useState } from 'react';
import type { ProgressPhoto } from '@/lib/db/types';
import { ANGULOS, anguloLabel } from '@/lib/progress-photos';
import { deletePhoto } from '@/lib/repositories/progress-photos';

async function borrarEnR2(key: string): Promise<void> {
  try {
    await fetch('/api/progress-photos', {
      method: 'DELETE',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ key }),
    });
  } catch {
    // best-effort
  }
}

export function ProgressGallery({ fotos }: { fotos: ProgressPhoto[] }) {
  const [reveladas, setReveladas] = useState<Set<string>>(new Set());

  function toggle(id: string) {
    setReveladas((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function borrar(foto: ProgressPhoto) {
    const key = await deletePhoto(foto.id);
    if (key) void borrarEnR2(key);
  }

  return (
    <div className="space-y-4">
      {ANGULOS.map((ang) => {
        const delAngulo = fotos
          .filter((f) => f.angulo === ang)
          .sort((a, b) => b.fecha - a.fecha);
        if (delAngulo.length === 0) return null;
        return (
          <section key={ang} className="space-y-2">
            <h3 className="label-mono text-[10px] text-muted-foreground">{anguloLabel[ang]}</h3>
            <div className="grid grid-cols-3 gap-2">
              {delAngulo.map((f) => {
                const revelada = reveladas.has(f.id);
                return (
                  <div key={f.id} className="space-y-1">
                    <button
                      type="button"
                      onClick={() => toggle(f.id)}
                      className="block w-full"
                      aria-label={`${anguloLabel[ang]} ${revelada ? 'ocultar' : 'revelar'}`}
                    >
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img
                        src={f.url}
                        alt={`Foto ${anguloLabel[ang]}`}
                        className={`h-28 w-full border-2 border-foreground object-cover transition ${revelada ? '' : 'blur-md'}`}
                      />
                    </button>
                    <div className="flex items-center justify-between gap-1">
                      <span className="label-mono text-[9px] text-muted-foreground">
                        {new Date(f.fecha).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit', year: '2-digit' })}
                      </span>
                      <button
                        type="button"
                        className="grid size-6 place-items-center text-muted-foreground hover:text-destructive"
                        aria-label="Eliminar foto"
                        onClick={() => void borrar(f)}
                      >
                        ✕
                      </button>
                    </div>
                    {f.nota && <p className="text-[10px] text-muted-foreground">{f.nota}</p>}
                  </div>
                );
              })}
            </div>
          </section>
        );
      })}
    </div>
  );
}
```

Note: the test asserts `className` contains `'blur'` by default — `blur-md` satisfies `toContain('blur')`. After reveal the class no longer contains `blur`. Keep that behavior.

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit` + `npx eslint components/progress-gallery.tsx`.

- [ ] **Step 5: Commit**

```bash
git add components/progress-gallery.tsx components/progress-gallery.test.tsx
git commit -m "feat(fotos): galería por ángulo con blur reveal + borrado"
```

---

## Task 4: Sección compuesta + integración en `/cuerpo`

**Files:**
- Create: `components/progress-photos-section.tsx`
- Modify: `app/cuerpo/page.tsx`

- [ ] **Step 0: READ FIRST**

Read `app/cuerpo/page.tsx` (estructura actual: header + `<BodyForm />` + tarjetas), `lib/repositories/progress-photos.ts` (`listPhotos`), `components/progress-photo-upload.tsx` + `components/progress-gallery.tsx`.

- [ ] **Step 1: Create the section** — create `components/progress-photos-section.tsx`:

```tsx
'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { listPhotos } from '@/lib/repositories/progress-photos';
import { ProgressPhotoUpload } from '@/components/progress-photo-upload';
import { ProgressGallery } from '@/components/progress-gallery';

export function ProgressPhotosSection() {
  const fotos = useLiveQuery(() => listPhotos(), []);

  return (
    <section className="space-y-3">
      <h2 className="label-mono text-[11px] text-muted-foreground">Fotos de progreso</h2>
      <ProgressPhotoUpload />
      {fotos === undefined ? (
        <p className="text-muted-foreground">Cargando…</p>
      ) : fotos.length === 0 ? (
        <p className="text-muted-foreground">Aún no has subido fotos. Añade la primera arriba.</p>
      ) : (
        <ProgressGallery fotos={fotos} />
      )}
    </section>
  );
}
```

- [ ] **Step 2: Integrate into `/cuerpo`** — in `app/cuerpo/page.tsx`:
1. Add import: `import { ProgressPhotosSection } from '@/components/progress-photos-section';`
2. Add `<ProgressPhotosSection />` at the end of the outer `<div className="space-y-6">`, AFTER the metrics block (the `{metrics === undefined ? … }` expression), as the last child.

- [ ] **Step 3: Typecheck + lint + build**

Run: `npx tsc --noEmit && npx eslint components/progress-photos-section.tsx app/cuerpo/page.tsx && npm run build`
Expected: clean; `/cuerpo` build OK.

- [ ] **Step 4: Commit**

```bash
git add components/progress-photos-section.tsx app/cuerpo/page.tsx
git commit -m "feat(fotos): sección Fotos de progreso en /cuerpo"
```

---

## Task 5: Verificación final

- [ ] **Step 1:** `npx vitest run && npx tsc --noEmit && npm run lint && npm run build`
Expected: todo verde.

---

## Self-Review (hecho)

- **Spec cobertura (D2b):** subir (ángulo + fecha hoy + nota + archivo `accept=image/*`+`capture`) → compress → POST → addPhoto ✓; galería agrupada por ángulo (orden frente/lado/espalda, solo con datos), fecha desc ✓; miniaturas difuminadas reveal-on-tap ✓; borrar (tombstone + DELETE R2 best-effort) ✓; sección en `/cuerpo` con estados cargando/vacío ✓; error de subida no crea entidad ✓.
- **Tipos consistentes:** `AnguloFoto`/`ANGULOS`/`anguloLabel` reutilizados; `addPhoto` input ↔ test; galería presentacional por props (test determinista); `deletePhoto` devuelve key → DELETE R2.
- **Casos límite:** sin fotos → invita a subir; ángulo sin datos → no se renderiza su sección; offline → aviso; nota vacía → `null`.
- **Sin placeholders:** todo el código presente. La comparación lado a lado es D2c.
