import { describe, it, expect } from 'vitest';
import { resolveServerWrite } from '@/lib/sync/server-merge';

describe('resolveServerWrite (LWW del servidor)', () => {
  it('acepta el entrante si es más nuevo o igual', () => {
    expect(resolveServerWrite(100, 200)).toBe(true);
    expect(resolveServerWrite(100, 100)).toBe(true);
  });
  it('rechaza el entrante si el servidor tiene algo más nuevo', () => {
    expect(resolveServerWrite(300, 200)).toBe(false);
  });
  it('acepta si no había nada en el servidor', () => {
    expect(resolveServerWrite(undefined, 50)).toBe(true);
  });
});
