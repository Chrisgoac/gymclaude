import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

const useSetting = vi.fn();
vi.mock('@/lib/use-setting', () => ({ useSetting: (...a: unknown[]) => useSetting(...a) }));

const getRachaSemanal = vi.fn();
vi.mock('@/lib/repositories/stats', () => ({ getRachaSemanal: (...a: unknown[]) => getRachaSemanal(...a) }));

import { StreakMini } from '@/components/streak-mini';

beforeEach(() => {
  useSetting.mockReturnValue([3, vi.fn()]);
  getRachaSemanal.mockResolvedValue({ actual: 4, mejor: 6 });
});

it('muestra la racha actual y enlaza a /logros', async () => {
  render(<StreakMini />);
  const link = await screen.findByRole('link', { name: /racha/i });
  expect(link).toHaveAttribute('href', '/logros');
  await waitFor(() => expect(screen.getByText(/4/)).toBeInTheDocument());
});
