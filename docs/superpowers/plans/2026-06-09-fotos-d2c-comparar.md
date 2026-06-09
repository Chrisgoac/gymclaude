# Fotos D2c — Comparación lado a lado (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Comparar dos fotos de progreso del mismo ángulo lado a lado (por defecto la más antigua vs la más reciente, seleccionables), integrado en la sección de `/cuerpo`.

**Architecture:** Un componente presentacional `progress-compare` dirigido por props (`fotos`). Solo se ofrece para ángulos con ≥2 fotos. Selector de ángulo (si hay varios que califican) + dos selectores de foto (A/B) → dos imágenes en paralelo con su fecha. Se añade a `progress-photos-section` reusando el mismo `useLiveQuery(listPhotos)`.

**Tech Stack:** Next.js 16 (client) · Tailwind "Brutalist Iron" · vitest (proyecto `app` jsdom).

**Nota:** Fase **D2c** (última de D2) del spec `docs/superpowers/specs/2026-06-09-fotos-progreso-design.md`. D2a (entidad/ruta) y D2b (subida/galería) ya mergeadas. Disponibles: tipo `ProgressPhoto`/`AnguloFoto`, `ANGULOS`/`anguloLabel` (`lib/progress-photos.ts`). Decisión de diseño: comparación **lado a lado**, no slider.

---

## File Structure

- **Create** `components/progress-compare.tsx` — comparador lado a lado (presentacional).
- **Create** `components/progress-compare.test.tsx`.
- **Modify** `components/progress-photos-section.tsx` — añade `<ProgressCompare fotos={fotos} />`.

---

## Task 1: Componente comparador

**Files:**
- Create: `components/progress-compare.tsx`
- Create: `components/progress-compare.test.tsx`

> Presentacional: recibe `fotos: ProgressPhoto[]`. Ángulos que califican = los que tienen ≥2 fotos. Si ninguno → no renderiza nada. Estado: `angulo` (primer ángulo que califica), `idA`/`idB`. Por defecto A = la más antigua del ángulo, B = la más reciente. Selector de ángulo solo si hay >1 ángulo que califica. Imágenes en `grid grid-cols-2` con su fecha debajo.

- [ ] **Step 0: READ FIRST**

Read `lib/progress-photos.ts` (`ANGULOS`/`anguloLabel`), `lib/db/types.ts` (`ProgressPhoto`/`AnguloFoto`), `components/progress-gallery.tsx` (idioms: `<img>` con `// eslint-disable-next-line @next/next/no-img-element`, formato de fecha `toLocaleDateString('es-ES', { day:'2-digit', month:'2-digit', year:'2-digit' })`, clases brutalist), `components/coach-chat.test.tsx` (mocks/RTL idioms).

- [ ] **Step 1: Write the failing test** — create `components/progress-compare.test.tsx`:

```tsx
import { it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ProgressPhoto } from '@/lib/db/types';
import { ProgressCompare } from '@/components/progress-compare';

const foto = (id: string, angulo: ProgressPhoto['angulo'], fecha: number): ProgressPhoto => ({
  id, userId: null, url: `https://r2/${id}.jpg`, key: `u1/progress/${id}.jpg`, fecha, angulo, nota: null, updatedAt: fecha, deletedAt: null,
});

it('no renderiza nada si ningún ángulo tiene 2+ fotos', () => {
  const { container } = render(<ProgressCompare fotos={[foto('a', 'frente', 1000), foto('b', 'lado', 2000)]} />);
  expect(container).toBeEmptyDOMElement();
});

it('con 2+ fotos de un ángulo muestra dos imágenes (A=más antigua, B=más reciente)', () => {
  render(<ProgressCompare fotos={[foto('viejo', 'frente', 1000), foto('nuevo', 'frente', 3000)]} />);
  const imgs = screen.getAllByRole('img');
  expect(imgs).toHaveLength(2);
  expect((imgs[0] as HTMLImageElement).src).toContain('viejo'); // A = más antigua
  expect((imgs[1] as HTMLImageElement).src).toContain('nuevo'); // B = más reciente
});

it('cambiar el selector A cambia la imagen mostrada', async () => {
  render(<ProgressCompare fotos={[
    foto('f1', 'frente', 1000), foto('f2', 'frente', 2000), foto('f3', 'frente', 3000),
  ]} />);
  const [selA] = screen.getAllByRole('combobox').filter((el) => el.getAttribute('aria-label')?.match(/foto a/i));
  await userEvent.selectOptions(selA, 'f2');
  const imgs = screen.getAllByRole('img') as HTMLImageElement[];
  expect(imgs[0].src).toContain('f2');
});
```

If the `combobox` filter by aria-label is awkward, give the two selects explicit `aria-label="Foto A"` / `aria-label="Foto B"` (the impl below does) and target via `screen.getByLabelText('Foto A')`.

- [ ] **Step 2: Run → FAIL** (`npx vitest run components/progress-compare.test.tsx`).

- [ ] **Step 3: Implement** — create `components/progress-compare.tsx`:

```tsx
'use client';

import { useState } from 'react';
import type { ProgressPhoto, AnguloFoto } from '@/lib/db/types';
import { ANGULOS, anguloLabel } from '@/lib/progress-photos';

function fechaCorta(ms: number): string {
  return new Date(ms).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit', year: '2-digit' });
}

export function ProgressCompare({ fotos }: { fotos: ProgressPhoto[] }) {
  // Ángulos que califican: ≥2 fotos. Por ángulo, fotos en orden cronológico asc.
  const porAngulo = (a: AnguloFoto) => fotos.filter((f) => f.angulo === a).sort((x, y) => x.fecha - y.fecha);
  const angulosOK = ANGULOS.filter((a) => porAngulo(a).length >= 2);

  const [angulo, setAngulo] = useState<AnguloFoto | null>(angulosOK[0] ?? null);
  const activo = angulo && angulosOK.includes(angulo) ? angulo : (angulosOK[0] ?? null);

  const serie = activo ? porAngulo(activo) : [];
  const [idA, setIdA] = useState<string>(serie[0]?.id ?? '');
  const [idB, setIdB] = useState<string>(serie[serie.length - 1]?.id ?? '');

  if (angulosOK.length === 0 || !activo) return null;

  const fotoA = serie.find((f) => f.id === idA) ?? serie[0];
  const fotoB = serie.find((f) => f.id === idB) ?? serie[serie.length - 1];

  function cambiarAngulo(a: AnguloFoto) {
    setAngulo(a);
    const s = porAngulo(a);
    setIdA(s[0]?.id ?? '');
    setIdB(s[s.length - 1]?.id ?? '');
  }

  return (
    <section className="brutal-box space-y-3 p-3">
      <div className="flex items-center justify-between">
        <h3 className="label-mono text-[10px] text-muted-foreground">Comparar</h3>
        {angulosOK.length > 1 && (
          <select
            aria-label="Ángulo a comparar"
            className="border-2 border-foreground bg-card px-2 py-1 text-sm"
            value={activo}
            onChange={(e) => cambiarAngulo(e.target.value as AnguloFoto)}
          >
            {angulosOK.map((a) => (
              <option key={a} value={a}>
                {anguloLabel[a]}
              </option>
            ))}
          </select>
        )}
      </div>

      <div className="grid grid-cols-2 gap-2">
        {[
          { lado: 'A', sel: idA, set: setIdA, foto: fotoA },
          { lado: 'B', sel: idB, set: setIdB, foto: fotoB },
        ].map(({ lado, sel, set, foto }) => (
          <div key={lado} className="space-y-1">
            <select
              aria-label={`Foto ${lado}`}
              className="w-full border-2 border-foreground bg-card px-1 py-1 text-[11px]"
              value={sel}
              onChange={(e) => set(e.target.value)}
            >
              {serie.map((f) => (
                <option key={f.id} value={f.id}>
                  {fechaCorta(f.fecha)}
                </option>
              ))}
            </select>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={foto.url}
              alt={`${anguloLabel[activo]} ${lado}`}
              className="h-44 w-full border-2 border-foreground object-cover"
            />
            <p className="label-mono text-center text-[9px] text-muted-foreground">{fechaCorta(foto.fecha)}</p>
          </div>
        ))}
      </div>
    </section>
  );
}
```

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit` + `npx eslint components/progress-compare.tsx`.

- [ ] **Step 5: Commit**

```bash
git add components/progress-compare.tsx components/progress-compare.test.tsx
git commit -m "feat(fotos): comparación lado a lado por ángulo"
```

---

## Task 2: Integrar en la sección

**Files:**
- Modify: `components/progress-photos-section.tsx`

- [ ] **Step 0: READ FIRST**

Read `components/progress-photos-section.tsx` (estructura actual: upload + estados + `<ProgressGallery>`).

- [ ] **Step 1: Integrate** — in `components/progress-photos-section.tsx`:
1. Add import: `import { ProgressCompare } from '@/components/progress-compare';`
2. In the populated branch (when `fotos` has length), render `<ProgressCompare fotos={fotos} />` BELOW `<ProgressGallery fotos={fotos} />` (the compare renders null on its own if no angle qualifies, so it's safe to always include it when there are photos).

Example of the populated branch:
```tsx
      ) : (
        <>
          <ProgressGallery fotos={fotos} />
          <ProgressCompare fotos={fotos} />
        </>
      )}
```

- [ ] **Step 2: Typecheck + lint + build**

Run: `npx tsc --noEmit && npx eslint components/progress-photos-section.tsx && npm run build`
Expected: clean; `/cuerpo` builds.

- [ ] **Step 3: Commit**

```bash
git add components/progress-photos-section.tsx
git commit -m "feat(fotos): comparador integrado en la sección de /cuerpo"
```

---

## Task 3: Verificación final

- [ ] **Step 1:** `npx vitest run && npx tsc --noEmit && npm run lint && npm run build`
Expected: todo verde.

---

## Self-Review (hecho)

- **Spec cobertura (D2c):** comparación lado a lado por ángulo ✓; solo se ofrece con ≥2 fotos del ángulo ✓; selector de ángulo si varios califican ✓; A/B seleccionables, por defecto más antigua vs más reciente ✓; dos imágenes en paralelo con fecha ✓; integrado en la sección de `/cuerpo` ✓.
- **Tipos consistentes:** `AnguloFoto`/`ANGULOS`/`anguloLabel` reutilizados; presentacional por props (test determinista); defensivo si el id seleccionado ya no existe (fallback a extremos de la serie).
- **Casos límite:** ningún ángulo con ≥2 → no renderiza nada; cambiar de ángulo resetea A/B a los extremos; la galería (D2b) sigue mostrando todo aunque el comparador no aplique.
- **Sin placeholders:** todo el código presente.
