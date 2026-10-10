import { describe, expect, it } from 'vitest';
import { ApiError } from '@/lib/api/v1/errors';
import fixture from '@/contracts/v1/fixtures/pull-pagination-cases.json';
import { MAX_SEQUENCE, decodeCursor, encodeCursor } from './cursor';

describe('cursor', () => {
  for (const v of [BigInt(0), BigInt(1), BigInt('9007199254740992'), BigInt('9007199254740993'), MAX_SEQUENCE]) {
    it(`round-trips ${v}`, () => {
      const c = encodeCursor(v);
      expect(c).toMatch(/^[A-Za-z0-9_-]+$/);
      expect(decodeCursor(c)).toBe(v);
    });
  }

  it('treats null and empty as the start', () => {
    expect(decodeCursor(null)).toBe(BigInt(0));
    expect(decodeCursor('')).toBe(BigInt(0));
  });

  const b64 = (s: string) => Buffer.from(s).toString('base64url');
  const bad = [
    '!!!', 'MA==', 'M', 'not base64 at all', b64('-1'), b64('01'), b64('1.5'), b64('abc'), b64(' 1'),
    b64('0x10'), b64('9223372036854775808'), b64('99999999999999999999999999'), 'x'.repeat(64),
  ];
  for (const c of bad) {
    it(`rejects ${JSON.stringify(c)}`, () => {
      try {
        decodeCursor(c);
      } catch (e) {
        expect(e).toBeInstanceOf(ApiError);
        expect((e as ApiError).status).toBe(400);
        expect((e as ApiError).code).toBe('INVALID_CURSOR');
        return;
      }
      throw new Error('expected INVALID_CURSOR');
    });
  }

  for (const c of fixture.cursorRoundTrips) {
    it(`fixture round-trips ${c}`, () => expect(decodeCursor(encodeCursor(BigInt(c))).toString()).toBe(c));
  }
  for (const c of fixture.invalidCursors) {
    it(`fixture rejects: ${c.name}`, () => {
      expect(() => decodeCursor(c.cursor)).toThrow(expect.objectContaining({ code: 'INVALID_CURSOR', status: 400 }));
    });
  }

  it('refuses to encode out-of-range values', () => {
    expect(() => encodeCursor(-BigInt(1))).toThrow(ApiError);
    expect(() => encodeCursor(MAX_SEQUENCE + BigInt(1))).toThrow(ApiError);
  });
});
