import { describe, it, expect } from 'vitest';

describe('arnés de tests', () => {
  it('suma correctamente', () => {
    expect(1 + 1).toBe(2);
  });

  it('tiene IndexedDB disponible (fake-indexeddb)', () => {
    expect(typeof indexedDB).not.toBe('undefined');
  });
});
