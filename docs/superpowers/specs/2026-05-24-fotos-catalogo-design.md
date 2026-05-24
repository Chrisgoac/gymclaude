# GymLog — Fotos por defecto del catálogo (renders 3D)

- **Fecha:** 2026-05-24
- **Estado:** Aprobado (pendiente de revisión final del usuario)
- **Depende de:** feature "Fotos de ejercicios" (R2 + `ExercisePhoto`) ya desplegada; catálogo seed de 27 ejercicios en `lib/db/seed.ts`.

## Resumen

Dar a los **27 ejercicios del catálogo precargado** una **imagen por defecto** (estilo render
3D, sin personas reales) que la app muestra como *fallback* cuando el usuario no ha subido foto
propia. Las imágenes se **generan gratis** con **Pollinations.ai** mediante un script de una sola
vez, y se **empaquetan en `/public`** (activos versionados, offline, sin credenciales). No tocan
la entidad `ExercisePhoto` (que sigue siendo para fotos del usuario, en R2).

## Decisiones (brainstorming)

| Tema | Decisión |
|------|----------|
| Estilo | Renders 3D / ilustración (sin personas reales). |
| Fuente | **Pollinations.ai** (gratis, sin API key, scriptable; modelos abiertos tipo FLUX). |
| Hosting | **`/public/catalog/<slug>.jpg`** (versionado, offline; no R2). |
| Mecanismo | Mapa estático `seedId → url` + resolución **foto del usuario > render por defecto > placeholder**. NO usa `ExercisePhoto` ni sync. |
| Alcance | Los 27 ejercicios seed (`lib/db/seed.ts`). |

## Componentes

### 1. Script de generación (`scripts/generate-catalog-photos.mjs`)

One-time, ejecutado por el desarrollador/controlador (no en runtime, no en build).

- Tiene un mapa **`slug → prompt en inglés`** para los 27 (p. ej. `press-banca → "barbell bench
  press"`, `sentadilla → "barbell back squat"`, …), porque los modelos rinden mejor en inglés.
- Sufijo de estilo consistente para todos: `", clean 3D render, neutral grey mannequin figure,
  plain studio background, isometric view, centered, no text, no watermark"`.
- Para cada slug: descarga de
  `https://image.pollinations.ai/prompt/<prompt-url-encoded>?width=600&height=600&model=flux&nologo=true&seed=<fijo>`
  y guarda en `public/catalog/<slug>.jpg`.
- **Idempotente**: si `public/catalog/<slug>.jpg` ya existe y pesa > 0, lo salta.
- **Reintentos**: hasta 3 intentos por imagen con espera; si falla del todo, lo registra y sigue
  (ese slug se quedará sin default).
- Al terminar imprime la lista de slugs generados con éxito (para construir el mapa).
- Sin dependencias nuevas (usa `fetch` global de Node + `fs`).

### 2. Mapa + resolución (`lib/catalog-photos.ts`)

```ts
// Solo los slugs efectivamente generados (evita 404 por los que fallaran).
export const CATALOG_PHOTOS: Record<string, string> = {
  'seed-press-banca': '/catalog/press-banca.jpg',
  // … una entrada por cada render generado con éxito
};

/** URL de imagen a mostrar: foto del usuario > render por defecto del catálogo > undefined. */
export function resolveExercisePhotoUrl(
  exerciseId: string,
  userPhotoUrl?: string,
): string | undefined {
  return userPhotoUrl ?? CATALOG_PHOTOS[exerciseId];
}
```

- Las URLs son **relativas** (`/catalog/<slug>.jpg`) servidas desde `/public`.
- El contenido de `CATALOG_PHOTOS` se rellena a partir de la salida del script (solo éxitos).

### 3. Cableado del fallback (UI)

En los 4 puntos que ya pintan miniatura, sustituir el uso directo de la foto del usuario por
`resolveExercisePhotoUrl(exerciseId, userPhoto?.url)`:

- `components/exercise-list.tsx` — usa `getPhotosMap()`; por fila: `resolveExercisePhotoUrl(ex.id, fotos?.get(ex.id)?.url)`.
- `components/exercise-photo-picker.tsx` — la vista previa muestra la URL resuelta (default si no hay
  foto propia). "Cambiar/Añadir foto" sigue subiendo una foto de usuario (que sobrescribe el default);
  "Quitar" borra la foto de usuario y la vista vuelve a mostrar el render por defecto. Matiz: el botón
  "Quitar" solo aparece si hay **foto de usuario** (no para quitar el default).
- `components/logged-exercise-card.tsx` y `components/routine-day-exercise-row.tsx` — miniatura usando
  la URL resuelta.

Efecto: los ejercicios del catálogo muestran su render por defecto; los propios siguen mostrando
placeholder salvo que les subas foto; subir foto propia siempre manda.

### 4. Tests

- `lib/catalog-photos.test.ts`: `resolveExercisePhotoUrl` devuelve la foto del usuario si la hay;
  si no, el default del catálogo; si ninguna, `undefined`. Y que `CATALOG_PHOTOS` tiene entradas
  con forma `/catalog/...`.
- Mantener verdes los tests existentes: `exercise-list.test.tsx`, `exercise-photo-picker.test.tsx`,
  `logged-exercise-card.test.tsx`, `routine-day-exercise-row.test.tsx` usan ejercicios **propios**
  (id no-`seed-`), que no están en `CATALOG_PHOTOS` → placeholder igual que ahora.

## Flujo de ejecución (orden)

1. (Controlador) corre el script → genera `public/catalog/*.jpg`.
2. Rellenar `CATALOG_PHOTOS` con los slugs generados.
3. Cablear los 4 componentes + tests.
4. Verificar (tests/tsc/lint/build) y desplegar.

## Fuera de alcance (YAGNI)

- Regenerar imágenes en runtime o en build.
- Editor de prompts / selección de variantes.
- Imágenes genéricas por grupo muscular para los que fallen (quedan con placeholder).
- Hospedaje en R2 (se decidió `/public`).

## Notas

- **Calidad variable** de Pollinations: los renders pueden salir imperfectos; los que salgan mal se
  pueden regenerar (cambiando seed/prompt y re-corriendo el script para ese slug) o sustituir por una
  foto propia desde la app. Los que fallen del todo no entran en el mapa → placeholder.
- Build con `--webpack` (Serwist). Las imágenes de `/public/catalog` se sirven como estáticos; pueden
  engordar algo el precache del SW (aceptable, ~1–2 MB en total).
- Licencia: Pollinations declara las salidas libres de uso; el estilo evita personas reales.
