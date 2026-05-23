import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BottomNav } from '@/components/bottom-nav';

vi.mock('next/navigation', () => ({
  usePathname: () => '/',
}));

describe('BottomNav', () => {
  it('muestra las cuatro pestañas', () => {
    render(<BottomNav />);
    expect(screen.getByText('Entrenar')).toBeInTheDocument();
    expect(screen.getByText('Rutinas')).toBeInTheDocument();
    expect(screen.getByText('Progreso')).toBeInTheDocument();
    expect(screen.getByText('Historial')).toBeInTheDocument();
  });
});
