# Diseño — Integrar 117 ejercicios al catálogo (scraping simplyfitness)

Fecha: 2026-06-07
Estado: implementado

## Objetivo
Ampliar el catálogo de 26 a 143 ejercicios con datos importados (con permiso del dueño, el usuario es cofundador) de simplyfitness.com: nombre (ES), imagen, descripción y ejecución (traducidas al español).

## Origen de datos (scraping)
- Índice: `https://www.simplyfitness.com/pages/workout-exercise-guides` → 145 fichas (se descartan guías de categoría, calculadoras y los 26 ya añadidos → **117 nuevos**).
- Por ficha: `<h1 class="exo-h1">` (nombre EN), imagen `cdn.shopify…/files/…_600x600.png`, secciones `Starting position` (→ descripción) y `Execution`. También `Equipment required` y `Main muscles` para clasificar.
- Dataset crudo (EN + img) en `docs/scraping/simplyfitness-ejercicios.json`.

## Procesado
- **Traducción** descripción + ejecución EN→ES con un workflow multi-agente (subagentes en paralelo). 1 ejercicio (`bird-dog`) traducido a mano.
- **Clasificación** `grupoMuscular`/`equipamiento`/`tipo` por heurística sobre el slug + correcciones puntuales (p.ej. `kickback`→tríceps/mancuerna, `*-pulldown`→polea, `front-squat`→barra). kettlebell/banda/balón → equipamiento `otro`.
- Resultado ensamblado en `lib/db/catalog-extra.ts` (`CATALOG_EXTRA: Exercise[]`, 117 entradas, generado, no editar a mano).

## Cambios en la app
- `Exercise`: nuevos campos opcionales `descripcion?`, `execution?` (no indexados; sin bump de Dexie). No se sincronizan (los seeds son globales, `esPersonalizado:false`); sin columnas nuevas en Neon.
- `lib/db/seed.ts`: `CATALOG_SEED = [...26 base, ...CATALOG_EXTRA]` (143). **Siembra aditiva** `seedCatalog()` (alias `seedCatalogIfEmpty`): añade los ids que falten sin pisar nada → los usuarios con BD ya poblada reciben los 117 al arrancar.
- Imágenes por defecto: 117 PNG en `public/catalog/<slug>.png` (~12 MB nuevos; 143 en total). `lib/catalog-photos.ts` regenerado desde la carpeta (143 entradas). Resolución usuario > defecto > nada (ya existente).
- Ficha del catálogo (`/ejercicios/[id]`, solo lectura): muestra **Descripción** y **Ejecución** (`whitespace-pre-line`).

## Tests
`seed.test` (143 + siembra aditiva), `catalog-photos.test` (143), `catalog-photos` resolución. **159 tests**, tsc/lint/build OK.

## Notas
- Los 26 originales siguen sin descripción/ejecución (se pueden añadir en otra tanda).
- Reproducir/añadir: ampliar el dataset en `docs/scraping/`, reclasificar/traducir y regenerar `catalog-extra.ts`.
