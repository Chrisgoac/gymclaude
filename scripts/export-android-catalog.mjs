// Exports the PWA exercise catalog (lib/db/seed.ts + lib/db/catalog-extra.ts) as the Android
// offline asset. Deterministic: rows sorted by id, fixed key order, 2-space JSON, trailing newline.
// Usage: npm run export:android-catalog   (needs tsx, which loads the TypeScript catalog sources)
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { CATALOG_SEED } from '../lib/db/seed.ts';

/** Bump when the catalog content changes so installed apps re-import it. */
export const CATALOG_VERSION = 1;

export const OUTPUT_PATH = fileURLToPath(
  new URL('../android/app/src/main/assets/exercises-v1.json', import.meta.url),
);

const FIELDS = ['id', 'nombre', 'grupoMuscular', 'equipamiento', 'tipo', 'videoUrl', 'descripcion', 'execution'];

export function buildCatalogAsset(catalog = CATALOG_SEED) {
  const exercises = catalog
    .filter((e) => e.userId === null && !e.esPersonalizado)
    .map((e) => Object.fromEntries(FIELDS.filter((k) => e[k] != null && e[k] !== '').map((k) => [k, e[k]])))
    .sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return { version: CATALOG_VERSION, exercises };
}

export function serializeCatalogAsset(asset = buildCatalogAsset()) {
  return JSON.stringify(asset, null, 2) + '\n';
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  writeFileSync(OUTPUT_PATH, serializeCatalogAsset());
  console.log(`Wrote ${OUTPUT_PATH}`);
}
