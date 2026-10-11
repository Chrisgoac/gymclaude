// Exports the PWA exercise catalog (lib/db/seed.ts + lib/db/catalog-extra.ts) as the Android
// offline asset. Deterministic: rows sorted by id, fixed key order, 2-space JSON, trailing newline.
// Usage: npm run export:android-catalog   (needs tsx, which loads the TypeScript catalog sources)
import { createHash } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { CATALOG_SEED } from '../lib/db/seed.ts';

/** Bump when the catalog content changes so installed apps re-import it. */
export const CATALOG_VERSION = 1;

/**
 * SHA-256 of the exported exercises (`JSON.stringify(asset.exercises)`) that CATALOG_VERSION
 * describes. The test fails when the content changes without both being updated: bump the version,
 * then paste the new hash here.
 */
export const CATALOG_CONTENT_SHA256 = '0075326198cbdd813cb9a6041cc3ee5098dc3f6bced93a340b883cede4d432ea';

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

/** The hash compared against CATALOG_CONTENT_SHA256 (content only; the version is not part of it). */
export function catalogContentHash(asset = buildCatalogAsset()) {
  return createHash('sha256').update(JSON.stringify(asset.exercises)).digest('hex');
}

export function serializeCatalogAsset(asset = buildCatalogAsset()) {
  return JSON.stringify(asset, null, 2) + '\n';
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  writeFileSync(OUTPUT_PATH, serializeCatalogAsset());
  console.log(`Wrote ${OUTPUT_PATH}`);
}
