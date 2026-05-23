import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { CATALOG_SEED, seedCatalogIfEmpty } from '@/lib/db/seed';

describe('sembrado del catálogo', () => {
  beforeEach(async () => { await db.exercises.clear(); });

  it('contiene exactamente 26 ejercicios sin slugs duplicados', async () => {
    expect(CATALOG_SEED.length).toBe(26);
    const ids = new Set(CATALOG_SEED.map((e) => e.id));
    expect(ids.size).toBe(26);
  });

  it('siembra el catálogo cuando la tabla está vacía', async () => {
    await seedCatalogIfEmpty();
    expect(await db.exercises.count()).toBe(CATALOG_SEED.length);
  });

  it('no duplica si ya hay datos', async () => {
    await seedCatalogIfEmpty();
    await seedCatalogIfEmpty();
    expect(await db.exercises.count()).toBe(CATALOG_SEED.length);
  });

  it('todos los seeds son globales (userId null) y no personalizados', async () => {
    await seedCatalogIfEmpty();
    const all = await db.exercises.toArray();
    expect(all.every((e) => e.userId === null && e.esPersonalizado === false)).toBe(true);
  });
});
