# Diseño — Fotos por defecto del catálogo (compartidas), v2

Fecha: 2026-06-07
Estado: implementado

## Objetivo
Que los 26 ejercicios del catálogo seed tengan una foto por defecto que **vea todo el mundo**, sin tocar R2 ni el sync ni permisos. La foto que sube el usuario (ExercisePhoto) tiene prioridad.

## Decisiones del usuario
- **Mecanismo**: empaquetadas con la app (mapa estático en `public/`). Añadir/cambiar = commit + redeploy. (Descartado: globales en servidor, por complejidad y riesgo de que un usuario pise las globales.)
- **Origen**: el usuario pasa URLs (una por ejercicio); se descargan a `public/catalog/`. Imágenes elegidas: set coherente de líneas 600×600 (cdn.shopify de fitnessprogramer).

## Implementación
- `scripts/fetch-catalog-photos.mjs`: mapa `slug → URL`, descarga idempotente con reintentos a `public/catalog/<slug>.png` (26/26, ~2,9 MB).
- `lib/catalog-photos.ts`: `CATALOG_PHOTOS` (`seed-<slug> → /catalog/<slug>.png`, 26 entradas) + `resolveExercisePhotoUrl(exerciseId, userPhotoUrl?)` = **usuario > por defecto > undefined**.
- Cableado en los 4 puntos de miniatura: `exercise-list`, `logged-exercise-card`, `routine-day-exercise-row`, `exercise-photo-picker`. En el selector, "Quitar" sigue saliendo solo si hay foto propia (la por defecto no se quita; subir una propia la sobreescribe).
- No toca `ExercisePhoto`, R2, sync ni backup.

## Mapeo nombre→slug (por nombre, no por posición; el usuario reordenó)
press-banca=Barbell Bench Press, press-inclinado-mancuerna=Incline Dumbbell Bench Press, aperturas-polea=Cable Crossover, fondos=Parallel Dip Bar, peso-muerto=Dumbbell Deadlift, dominadas=Pull Up, jalon-al-pecho=Wide-Grip Pulldown, remo-barra=Barbell Row, remo-mancuerna=Dumbbell Bent Over Rows, press-militar=Standing Barbell Shoulder Press, elevaciones-laterales=Dumbbell Lateral Raise, pajaros=Bent-Over Lateral Raise, curl-barra=Barbell Curl, curl-martillo=Hammer Curl, extension-polea=Triceps Pressdown, press-frances=Lying Triceps Extension, sentadilla=Squat, prensa=Leg Press, extension-cuadriceps=Leg Extension, zancada=Lunge, curl-femoral=Lying Leg Curl, peso-muerto-rumano=Barbell Romanian Deadlift, hip-thrust=Barbell Hip Thrust, elevacion-talones=Standing Calf Raise, crunch=Crunch, plancha=Plank.

## Tests
`lib/catalog-photos.test.ts` (prioridad de resolución, 26 entradas). 158 tests verdes, tsc/lint/build OK.

## Mantenimiento
Para añadir/cambiar una imagen: editar el mapa de `scripts/fetch-catalog-photos.mjs`, re-correr (`--force` para sobrescribir), actualizar `lib/catalog-photos.ts` si cambia el set, commit + deploy.
