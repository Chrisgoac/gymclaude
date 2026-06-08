import { it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { RestTimer } from '@/components/rest-timer';

it('no muestra nada antes de arrancar (startKey 0)', () => {
  const { container } = render(<RestTimer startKey={0} targetSeconds={90} />);
  expect(container).toBeEmptyDOMElement();
});

it('muestra el tiempo objetivo al arrancar', () => {
  render(<RestTimer startKey={1} targetSeconds={90} />);
  expect(screen.getByText('1:30')).toBeInTheDocument();
});

it('arranca en 0:00 cuando no hay objetivo (cuenta arriba)', () => {
  render(<RestTimer startKey={1} />);
  expect(screen.getByText('0:00')).toBeInTheDocument();
});
