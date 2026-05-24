import { describe, it, expect, beforeEach } from 'vitest';
import { getGymFilter, setGymFilter } from '@/lib/gym-filter';

beforeEach(() => localStorage.clear());

describe('gymFilter', () => {
  it('por defecto es "all"', () => {
    expect(getGymFilter()).toBe('all');
  });
  it('persiste el valor elegido', () => {
    setGymFilter('g1');
    expect(getGymFilter()).toBe('g1');
    expect(localStorage.getItem('gymlog.gymFilter')).toBe('g1');
  });
});
