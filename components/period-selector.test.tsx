import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { PeriodSelector } from '@/components/period-selector';

describe('PeriodSelector', () => {
  it('muestra las 4 opciones y marca la activa', () => {
    render(<PeriodSelector value="4s" onChange={() => {}} />);
    expect(screen.getByText('4 sem').className).toContain('bg-primary');
    expect(screen.getByText('Todo')).toBeInTheDocument();
  });
  it('llama onChange con el id al pulsar', () => {
    const onChange = vi.fn();
    render(<PeriodSelector value="4s" onChange={onChange} />);
    fireEvent.click(screen.getByText('3 meses'));
    expect(onChange).toHaveBeenCalledWith('3m');
  });
});
