// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, it, expect } from 'vitest';
import { EQUIPMENTS, MUSCLE_GROUPS } from '@/lib/db/types';
import { CATALOG_SEED } from '@/lib/db/seed';
// @ts-expect-error plain .mjs script without type declarations
import {
  buildCatalogAsset,
  catalogContentHash,
  serializeCatalogAsset,
  CATALOG_CONTENT_SHA256,
  CATALOG_VERSION,
  OUTPUT_PATH,
} from './export-android-catalog.mjs';

interface Row { id: string; nombre: string; grupoMuscular: string; equipamiento: string; tipo: string }

describe('export-android-catalog', () => {
  const asset = buildCatalogAsset() as { version: number; exercises: Row[] };

  it('keeps the PWA seed ids, one per catalog exercise', () => {
    expect(asset.exercises).toHaveLength(CATALOG_SEED.length);
    expect(new Set(asset.exercises.map((e) => e.id))).toEqual(new Set(CATALOG_SEED.map((e) => e.id)));
    for (const e of asset.exercises) expect(e.id.startsWith('seed-')).toBe(true);
  });

  it('has unique names and required muscle/equipment/type fields', () => {
    expect(new Set(asset.exercises.map((e) => e.nombre.toLowerCase())).size).toBe(asset.exercises.length);
    for (const e of asset.exercises) {
      expect(e.nombre.trim()).not.toBe('');
      expect(MUSCLE_GROUPS).toContain(e.grupoMuscular);
      expect(EQUIPMENTS).toContain(e.equipamiento);
      expect(['compuesto', 'aislamiento']).toContain(e.tipo);
    }
  });

  it('is sorted by id and serializes deterministically', () => {
    const ids = asset.exercises.map((e) => e.id);
    expect(ids).toEqual([...ids].sort());
    expect(serializeCatalogAsset(buildCatalogAsset())).toBe(serializeCatalogAsset(buildCatalogAsset()));
  });

  it('matches the committed Android asset (run npm run export:android-catalog when it drifts)', () => {
    expect(readFileSync(OUTPUT_PATH, 'utf8')).toBe(serializeCatalogAsset(asset));
  });

  it('changes content only together with a CATALOG_VERSION bump (update CATALOG_CONTENT_SHA256 with it)', () => {
    const hash = catalogContentHash(asset);
    expect(
      hash,
      `Catalog content changed (sha256 ${hash}) but CATALOG_VERSION is still ${CATALOG_VERSION}: ` +
        'bump CATALOG_VERSION so installed apps re-import it, then set CATALOG_CONTENT_SHA256 to the new hash.',
    ).toBe(CATALOG_CONTENT_SHA256);
  });

  it('the committed asset carries CATALOG_VERSION and the hashed content', () => {
    const committed = JSON.parse(readFileSync(OUTPUT_PATH, 'utf8'));
    expect(committed.version).toBe(CATALOG_VERSION);
    expect(catalogContentHash(committed)).toBe(CATALOG_CONTENT_SHA256);
  });
});
