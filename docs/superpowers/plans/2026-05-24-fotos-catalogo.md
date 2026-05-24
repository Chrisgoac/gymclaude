# Fotos por defecto del catálogo (renders 3D) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dar a los 27 ejercicios del catálogo una imagen por defecto (render 3D, generada gratis con Pollinations, empaquetada en `/public`) que se muestra como fallback cuando el usuario no ha subido foto propia.

**Architecture:** Un script de una sola vez genera los renders y los guarda en `public/catalog/<slug>.jpg`. Un mapa estático `lib/catalog-photos.ts` (`seedId → url`) + `resolveExercisePhotoUrl()` resuelven qué imagen mostrar: **foto del usuario > render por defecto > placeholder**. Los 4 puntos de miniatura ya existentes se cablean a ese resolver. No toca `ExercisePhoto` ni el sync.

**Tech Stack:** Next.js 16 (`--webpack`), React 19, Dexie, Vitest, Node `fetch` (script), Pollinations.ai. Estética Brutalist Iron.

**Spec:** `docs/superpowers/specs/2026-05-24-fotos-catalogo-design.md`

---

## File Structure

**Crear:**
- `scripts/generate-catalog-photos.mjs` — script one-time de generación (controlador lo corre).
- `lib/catalog-photos.ts` — mapa `seedId → url` + `resolveExercisePhotoUrl` + `lib/catalog-photos.test.ts`.
- `public/catalog/<slug>.jpg` × 27 — generadas por el script (binarios, versionados).

**Modificar:**
- `components/exercise-list.tsx`, `components/exercise-photo-picker.tsx`,
  `components/logged-exercise-card.tsx`, `components/routine-day-exercise-row.tsx` — usar el resolver.

---

## Task 1: Script de generación + generar los 27 renders (controlador)

> Esta tarea la ejecuta el **controlador** (red a Pollinations, es lento y conviene revisar la calidad de los renders). No es TDD.

**Files:** Create `scripts/generate-catalog-photos.mjs`; genera `public/catalog/*.jpg`.

- [ ] **Step 1: Crear el script**

Crea `scripts/generate-catalog-photos.mjs`:

```js
import { mkdir, writeFile, stat } from 'node:fs/promises';
import { join } from 'node:path';

// slug (sin 'seed-') → descripción en inglés para el prompt.
const PROMPTS = {
  'press-banca': 'barbell bench press',
  'press-inclinado-mancuerna': 'incline dumbbell chest press',
  'aperturas-polea': 'cable chest fly',
  'fondos': 'parallel bar chest dips',
  'dominadas': 'pull-ups on a bar',
  'jalon-al-pecho': 'lat pulldown machine',
  'remo-barra': 'barbell bent-over row',
  'remo-mancuerna': 'one-arm dumbbell row on bench',
  'peso-muerto': 'barbell deadlift',
  'press-militar': 'standing barbell overhead shoulder press',
  'elevaciones-laterales': 'dumbbell lateral raise',
  'pajaros': 'bent-over dumbbell reverse fly',
  'curl-barra': 'standing barbell biceps curl',
  'curl-martillo': 'dumbbell hammer curl',
  'extension-polea': 'cable triceps pushdown',
  'press-frances': 'lying barbell skull crusher triceps extension',
  'sentadilla': 'barbell back squat',
  'prensa': 'leg press machine',
  'extension-cuadriceps': 'leg extension machine',
  'zancada': 'dumbbell walking lunge',
  'curl-femoral': 'lying leg curl machine',
  'peso-muerto-rumano': 'romanian deadlift with barbell',
  'hip-thrust': 'barbell hip thrust',
  'elevacion-talones': 'standing calf raise machine',
  'crunch': 'abdominal crunch on floor',
  'plancha': 'plank exercise hold',
};

const STYLE = 'clean 3D render, neutral grey mannequin figure, plain light studio background, isometric view, centered, full body, no text, no watermark';
const OUT_DIR = 'public/catalog';
const SEED = 42;

async function existeNoVacio(path) {
  try {
    const s = await stat(path);
    return s.size > 0;
  } catch {
    return false;
  }
}

async function generar(slug, descripcion) {
  const out = join(OUT_DIR, `${slug}.jpg`);
  if (await existeNoVacio(out)) {
    console.log(`= ${slug} (ya existe, salto)`);
    return true;
  }
  const prompt = `${descripcion}, ${STYLE}`;
  const url = `https://image.pollinations.ai/prompt/${encodeURIComponent(prompt)}?width=600&height=600&model=flux&nologo=true&seed=${SEED}`;
  for (let intento = 1; intento <= 3; intento++) {
    try {
      const res = await fetch(url);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const buf = Buffer.from(await res.arrayBuffer());
      if (buf.length < 1000) throw new Error('respuesta demasiado pequeña');
      await writeFile(out, buf);
      console.log(`✓ ${slug} (${(buf.length / 1024).toFixed(0)} KB)`);
      return true;
    } catch (e) {
      console.log(`… ${slug} intento ${intento} falló: ${e.message}`);
      await new Promise((r) => setTimeout(r, 2000 * intento));
    }
  }
  console.log(`✗ ${slug} NO generado`);
  return false;
}

const okSlugs = [];
await mkdir(OUT_DIR, { recursive: true });
for (const [slug, desc] of Object.entries(PROMPTS)) {
  if (await generar(slug, desc)) okSlugs.push(slug);
}
console.log('\n=== slugs OK (' + okSlugs.length + '/' + Object.keys(PROMPTS).length + ') ===');
console.log(okSlugs.map((s) => `'seed-${s}': '/catalog/${s}.jpg',`).join('\n'));
```

- [ ] **Step 2: Ejecutar el script (controlador)**

Run: `node scripts/generate-catalog-photos.mjs`
Expected: imprime `✓` por cada imagen y, al final, el bloque de entradas `'seed-<slug>': '/catalog/<slug>.jpg',` de los que salieron bien. Reintenta los que fallen volviendo a correr el script (es idempotente). Objetivo: los 27.

- [ ] **Step 3: Verificar y revisar**

Run: `ls -la public/catalog/ | head -30`
Expected: 27 ficheros `.jpg` no vacíos. Echar un vistazo a unos cuantos (que parezcan el ejercicio razonablemente). Los que salgan claramente mal: borrar el `.jpg`, ajustar su prompt o `SEED`, y re-ejecutar.

- [ ] **Step 4: Commit**

```bash
git add scripts/generate-catalog-photos.mjs public/catalog
git commit -m "feat(catalogo): script Pollinations + 27 renders 3D por defecto en /public"
```

---

## Task 2: Mapa + resolver (`lib/catalog-photos.ts`)

**Files:** Create `lib/catalog-photos.ts`; Test `lib/catalog-photos.test.ts`.

- [ ] **Step 1: Escribir el test (falla)**

Crea `lib/catalog-photos.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { resolveExercisePhotoUrl, CATALOG_PHOTOS } from '@/lib/catalog-photos';

describe('catalog-photos', () => {
  it('la foto del usuario tiene prioridad', () => {
    expect(resolveExercisePhotoUrl('seed-press-banca', 'https://r2/mia.jpg')).toBe('https://r2/mia.jpg');
  });

  it('sin foto del usuario, devuelve el render por defecto del catálogo', () => {
    expect(resolveExercisePhotoUrl('seed-press-banca')).toBe('/catalog/press-banca.jpg');
  });

  it('sin foto ni default (ejercicio propio), devuelve undefined', () => {
    expect(resolveExercisePhotoUrl('id-aleatorio')).toBeUndefined();
  });

  it('el mapa contiene rutas /catalog/*.jpg', () => {
    expect(Object.values(CATALOG_PHOTOS).every((u) => u.startsWith('/catalog/') && u.endsWith('.jpg'))).toBe(true);
    expect(CATALOG_PHOTOS['seed-press-banca']).toBe('/catalog/press-banca.jpg');
  });
});
```

- [ ] **Step 2: Run (must fail)**

Run: `npm run test -- catalog-photos.test.ts`
Expected: FAIL (módulo inexistente).

- [ ] **Step 3: Implementar**

Crea `lib/catalog-photos.ts`. El objeto `CATALOG_PHOTOS` se rellena con **el bloque que imprimió el script en la Task 1** (una entrada por render generado con éxito). Si los 27 salieron bien, queda así:

```ts
/** Render 3D por defecto de cada ejercicio del catálogo (en /public/catalog). */
export const CATALOG_PHOTOS: Record<string, string> = {
  'seed-press-banca': '/catalog/press-banca.jpg',
  'seed-press-inclinado-mancuerna': '/catalog/press-inclinado-mancuerna.jpg',
  'seed-aperturas-polea': '/catalog/aperturas-polea.jpg',
  'seed-fondos': '/catalog/fondos.jpg',
  'seed-dominadas': '/catalog/dominadas.jpg',
  'seed-jalon-al-pecho': '/catalog/jalon-al-pecho.jpg',
  'seed-remo-barra': '/catalog/remo-barra.jpg',
  'seed-remo-mancuerna': '/catalog/remo-mancuerna.jpg',
  'seed-peso-muerto': '/catalog/peso-muerto.jpg',
  'seed-press-militar': '/catalog/press-militar.jpg',
  'seed-elevaciones-laterales': '/catalog/elevaciones-laterales.jpg',
  'seed-pajaros': '/catalog/pajaros.jpg',
  'seed-curl-barra': '/catalog/curl-barra.jpg',
  'seed-curl-martillo': '/catalog/curl-martillo.jpg',
  'seed-extension-polea': '/catalog/extension-polea.jpg',
  'seed-press-frances': '/catalog/press-frances.jpg',
  'seed-sentadilla': '/catalog/sentadilla.jpg',
  'seed-prensa': '/catalog/prensa.jpg',
  'seed-extension-cuadriceps': '/catalog/extension-cuadriceps.jpg',
  'seed-zancada': '/catalog/zancada.jpg',
  'seed-curl-femoral': '/catalog/curl-femoral.jpg',
  'seed-peso-muerto-rumano': '/catalog/peso-muerto-rumano.jpg',
  'seed-hip-thrust': '/catalog/hip-thrust.jpg',
  'seed-elevacion-talones': '/catalog/elevacion-talones.jpg',
  'seed-crunch': '/catalog/crunch.jpg',
  'seed-plancha': '/catalog/plancha.jpg',
};

/** URL de imagen a mostrar: foto del usuario > render por defecto del catálogo > undefined. */
export function resolveExercisePhotoUrl(exerciseId: string, userPhotoUrl?: string): string | undefined {
  return userPhotoUrl ?? CATALOG_PHOTOS[exerciseId];
}
```

> Importante: si en la Task 1 algún slug NO se generó, **elimina su línea** de `CATALOG_PHOTOS` (para no servir una imagen que da 404). El test `'el mapa contiene rutas /catalog/*.jpg'` no depende del número de entradas.

- [ ] **Step 4: Run (must pass)**

Run: `npm run test -- catalog-photos.test.ts`
Expected: PASS

- [ ] **Step 5: Verify**

Run: `npx tsc --noEmit && npm run lint`
Expected: clean.

- [ ] **Step 6: Commit**

```bash
git add lib/catalog-photos.ts lib/catalog-photos.test.ts
git commit -m "feat(catalogo): mapa de renders por defecto + resolveExercisePhotoUrl"
```

---

## Task 3: Cablear el fallback en los 4 puntos de miniatura

**Files:** Modify `components/exercise-list.tsx`, `components/exercise-photo-picker.tsx`,
`components/logged-exercise-card.tsx`, `components/routine-day-exercise-row.tsx`.

> Cambio aditivo: los ejercicios propios (id no-`seed-`) no están en `CATALOG_PHOTOS`, así que su comportamiento no cambia (placeholder salvo foto propia). Los tests existentes siguen verdes.

- [ ] **Step 1: `exercise-list.tsx`** — usar el resolver para la miniatura de cada fila.

Añade el import:
```tsx
import { resolveExercisePhotoUrl } from '@/lib/catalog-photos';
```
Sustituye el `.map` de items (de retorno implícito a cuerpo de bloque, calculando `url` con el resolver). El bloque actual es `{items.map((ex) => (<li>…</li>))}`; déjalo así:
```tsx
            {items.map((ex) => {
              const url = resolveExercisePhotoUrl(ex.id, fotos?.get(ex.id)?.url);
              return (
                <li key={ex.id}>
                  <Link href={`/ejercicios/${ex.id}`} className="flex items-center gap-3 p-2">
                    {url ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img src={url} alt="" className="size-12 shrink-0 border-2 border-foreground object-cover" />
                    ) : (
                      <span className="size-12 shrink-0 border-2 border-foreground bg-card/50" aria-hidden="true" />
                    )}
                    <span className="flex-1 font-medium">{ex.nombre}</span>
                    <span className="label-mono text-[10px] text-muted-foreground">{equipmentLabel[ex.equipamiento]}</span>
                  </Link>
                </li>
              );
            })}
```

- [ ] **Step 2: `exercise-photo-picker.tsx`** — vista previa con la URL resuelta.

Añade el import:
```tsx
import { resolveExercisePhotoUrl } from '@/lib/catalog-photos';
```
Justo después de `const foto = useLiveQuery(...)`, añade:
```tsx
  const fotoUrl = resolveExercisePhotoUrl(exerciseId, foto?.url);
```
Sustituye el bloque de vista previa (`{foto ? <img src={foto.url} .../> : <div placeholder/>}`) por uno basado en `fotoUrl`:
```tsx
      {fotoUrl ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={fotoUrl} alt="Foto del ejercicio" className="h-40 w-full border-2 border-foreground object-cover" />
      ) : (
        <div className="grid h-40 w-full place-items-center border-2 border-dashed border-foreground bg-card/50">
          <span className="label-mono text-[11px] text-muted-foreground">Sin foto</span>
        </div>
      )}
```
(El botón "Quitar" sigue condicionado a `foto` —la foto del usuario—, no a `fotoUrl`: solo se quita una foto propia, no el render por defecto. El label sigue siendo `foto ? 'Cambiar foto' : 'Añadir foto'`.)

- [ ] **Step 3: `logged-exercise-card.tsx`** — miniatura con la URL resuelta.

Añade el import:
```tsx
import { resolveExercisePhotoUrl } from '@/lib/catalog-photos';
```
Tras `const foto = useLiveQuery(() => getPhoto(loggedExercise.exerciseId), ...)`, añade:
```tsx
  const fotoUrl = resolveExercisePhotoUrl(loggedExercise.exerciseId, foto?.url);
```
En la cabecera, sustituye `{foto && (<img src={foto.url} .../>)}` por:
```tsx
            {fotoUrl && (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={fotoUrl} alt="" className="size-7 shrink-0 border border-background object-cover" />
            )}
```

- [ ] **Step 4: `routine-day-exercise-row.tsx`** — miniatura con la URL resuelta.

Añade el import:
```tsx
import { resolveExercisePhotoUrl } from '@/lib/catalog-photos';
```
Tras `const foto = useLiveQuery(() => getPhoto(routineExercise.exerciseId), ...)`, añade:
```tsx
  const fotoUrl = resolveExercisePhotoUrl(routineExercise.exerciseId, foto?.url);
```
Sustituye `{foto && (<img src={foto.url} .../>)}` por:
```tsx
          {fotoUrl && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={fotoUrl} alt="" className="size-8 shrink-0 border-2 border-foreground object-cover" />
          )}
```

- [ ] **Step 5: Verify**

Run: `npm run test && npx tsc --noEmit && npm run lint`
Expected: todo verde (los tests existentes usan ejercicios propios → sin default → mismo comportamiento; los de la lista/picker no asumen default).

- [ ] **Step 6: Commit**

```bash
git add components/exercise-list.tsx components/exercise-photo-picker.tsx components/logged-exercise-card.tsx components/routine-day-exercise-row.tsx
git commit -m "feat(catalogo): mostrar render por defecto (fallback foto usuario > default > placeholder)"
```

---

## Task 4: Verificación final + deploy

**Files:** ninguno.

- [ ] **Step 1: Suite + tipos + lint**

Run: `npm run test && npx tsc --noEmit && npm run lint`
Expected: todo verde.

- [ ] **Step 2: Build de producción**

Run: `npm run build`
Expected: `Compiled successfully`; los estáticos de `/public/catalog` se incluyen.

- [ ] **Step 3: Commit final (si quedara algo)**

```bash
git add -A && git commit -m "chore: verificación final fotos de catálogo" || echo "nada que commitear"
```

- [ ] **Step 4: Deploy (controlador)**

Tras merge a `main`: `vercel --prod`. No hay cambios de esquema ni env nuevas; es solo código + estáticos.

---

## Notas de cierre

- La generación (Task 1) es one-time y manual; los renders quedan versionados en `public/catalog`. Para regenerar uno: borra su `.jpg`, ajusta prompt/seed en el script y re-ejecuta.
- `/public/catalog` añade ~1–2 MB al repo y al deploy; aceptable.
- Si Pollinations falla persistentemente para algún slug, ese ejercicio se queda con placeholder (su línea no entra en `CATALOG_PHOTOS`); se puede reintentar más tarde.
- Caché offline de estos estáticos: la sirve Next desde `/public`; si se quiere precache explícito en el SW, es una mejora menor aparte.
