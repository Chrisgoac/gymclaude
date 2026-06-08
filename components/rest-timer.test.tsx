import { it, expect, vi, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
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

it('vibra exactamente una vez al llegar a 0 (cuenta atrás)', async () => {
  vi.useFakeTimers();
  const vibrate = vi.fn();
  vi.stubGlobal('navigator', { vibrate });

  try {
    render(<RestTimer startKey={1} targetSeconds={2} />);

    // Avanza más allá del final; el timer debe detenerse en 0 tras 2 s.
    await act(async () => {
      vi.advanceTimersByTime(5000);
    });

    expect(vibrate).toHaveBeenCalledTimes(1);
    expect(vibrate).toHaveBeenCalledWith(200);
  } finally {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  }
});
