import { describe, it, expect, beforeEach } from 'vitest';
import {
  getSuggestNextRoutine, setSuggestNextRoutine,
} from '@/lib/settings';

beforeEach(() => localStorage.clear());

describe('suggestNextRoutine', () => {
  it('por defecto es false', () => {
    expect(getSuggestNextRoutine()).toBe(false);
  });
  it('persiste el valor elegido', () => {
    setSuggestNextRoutine(true);
    expect(getSuggestNextRoutine()).toBe(true);
    setSuggestNextRoutine(false);
    expect(getSuggestNextRoutine()).toBe(false);
  });
});
