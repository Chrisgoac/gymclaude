// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { buildFixtures, OUTPUT_DIR, serialize } from './export-domain-fixtures.mjs';

describe('domain parity fixtures', () => {
  it.each(Object.entries(buildFixtures()))('%s is up to date (run npm run export:domain-fixtures)', (file, content) => {
    expect(readFileSync(OUTPUT_DIR + file, 'utf8')).toBe(serialize(content));
  });
});
