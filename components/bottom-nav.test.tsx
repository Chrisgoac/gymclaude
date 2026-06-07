import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { usePathname } from 'next/navigation';
import { BottomNav } from '@/components/bottom-nav';

vi.mock('next/navigation', () => ({
  usePathname: vi.fn(),
}));

const mockedUsePathname = vi.mocked(usePathname);

describe('BottomNav', () => {
  it('muestra las cinco pestañas y NO incluye Ajustes', () => {
    mockedUsePathname.mockReturnValue('/');
    render(<BottomNav />);
    expect(screen.getByText('Entrenar')).toBeInTheDocument();
    expect(screen.getByText('Rutinas')).toBeInTheDocument();
    expect(screen.getByText('Ejercicios')).toBeInTheDocument();
    expect(screen.getByText('Progreso')).toBeInTheDocument();
    expect(screen.getByText('Historial')).toBeInTheDocument();
    expect(screen.queryByText('Ajustes')).not.toBeInTheDocument();
  });

  it('marca Ejercicios como activa en su ruta y subrutas', () => {
    mockedUsePathname.mockReturnValue('/ejercicios/nuevo');
    render(<BottomNav />);
    expect(screen.getByText('Ejercicios').closest('a')!.className).toContain('text-primary');
  });

  it('marca como activa la pestaña de la ruta actual', () => {
    mockedUsePathname.mockReturnValue('/rutinas');
    render(<BottomNav />);
    expect(screen.getByText('Rutinas').closest('a')!.className).toContain('text-primary');
    expect(screen.getByText('Entrenar').closest('a')!.className).toContain('text-muted-foreground');
  });

  it('marca Entrenar como activa en las subrutas del registro', () => {
    mockedUsePathname.mockReturnValue('/entrenar/abc123');
    render(<BottomNav />);
    expect(screen.getByText('Entrenar').closest('a')!.className).toContain('text-primary');
  });
});
