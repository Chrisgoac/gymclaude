import { describe, it, expect } from 'vitest';
import {
  compareVersionTuple,
  decideVersionedWrite,
  resolveServerWrite,
} from '@/lib/sync/server-merge';
import fixture from '@/contracts/v1/fixtures/conflict-cases.json';

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

describe('compareVersionTuple', () => {
  const t = (updatedAt: number, deviceId: string, mutationId: string) => ({ updatedAt, deviceId, mutationId });

  it('orders by updatedAt first, numerically', () => {
    expect(compareVersionTuple(t(9, 'z', 'z'), t(10, 'a', 'a'))).toBe(-1);
    expect(compareVersionTuple(t(10, 'a', 'a'), t(9, 'z', 'z'))).toBe(1);
  });
  it('breaks timestamp ties by deviceId, then mutationId', () => {
    expect(compareVersionTuple(t(1, 'b', 'a'), t(1, 'a', 'z'))).toBe(1);
    expect(compareVersionTuple(t(1, 'a', 'b'), t(1, 'a', 'a'))).toBe(1);
    expect(compareVersionTuple(t(1, 'a', 'a'), t(1, 'a', 'b'))).toBe(-1);
  });
  it('returns 0 only for identical tuples', () => {
    expect(compareVersionTuple(t(1, 'a', 'a'), t(1, 'a', 'a'))).toBe(0);
  });
  it('compares strings by code unit, not locale', () => {
    // 'Z' (0x5A) < 'a' (0x61) por code unit; un localeCompare diría lo contrario.
    expect(compareVersionTuple(t(1, 'Z', 'm'), t(1, 'a', 'm'))).toBe(-1);
  });
});

describe('decideVersionedWrite (fixture compartido conflict-cases.json)', () => {
  const big = (v: string | null) => (v === null ? null : BigInt(v));
  for (const c of fixture.mergeCases) {
    it(c.name, () => {
      const existing = c.existing
        ? {
            updatedAt: c.existing.updatedAt,
            deviceId: c.existing.deviceId,
            mutationId: c.existing.mutationId,
            serverVersion: big(c.existing.serverVersion),
          }
        : null;
      const incoming = {
        updatedAt: c.incoming.updatedAt,
        deviceId: c.incoming.deviceId,
        mutationId: c.incoming.mutationId,
      };
      if (existing) {
        expect(
          compareVersionTuple(incoming, {
            updatedAt: existing.updatedAt,
            deviceId: existing.deviceId ?? '',
            mutationId: existing.mutationId ?? '',
          }),
        ).toBe(c.expected.compare);
      }
      expect(decideVersionedWrite(existing, incoming, big(c.baseServerVersion))).toEqual({
        apply: c.expected.apply,
        conflict: c.expected.conflict,
      });
    });
  }
});
