import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { usePathname } from 'next/navigation';
import { BottomNav } from '@/components/bottom-nav';

vi.mock('next/navigation', () => ({
  usePathname: vi.fn(),
}));

const mockedUsePathname = vi.mocked(usePathname);

describe('BottomNav', () => {
  it('muestra las cuatro pestañas', () => {
    mockedUsePathname.mockReturnValue('/');
    render(<BottomNav />);
    expect(screen.getByText('Entrenar')).toBeInTheDocument();
    expect(screen.getByText('Rutinas')).toBeInTheDocument();
    expect(screen.getByText('Progreso')).toBeInTheDocument();
    expect(screen.getByText('Historial')).toBeInTheDocument();
  });

  it('marca como activa la pestaña de la ruta actual', () => {
    mockedUsePathname.mockReturnValue('/rutinas');
    render(<BottomNav />);
    // En /rutinas, "Rutinas" está activa y "Entrenar" (/) no.
    expect(screen.getByText('Rutinas').className).toContain('text-primary');
    expect(screen.getByText('Entrenar').className).toContain('text-muted-foreground');
  });
});
