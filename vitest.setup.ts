import '@testing-library/jest-dom/vitest';
import 'fake-indexeddb/auto';

// Recharts (ResponsiveContainer) usa ResizeObserver, ausente en jsdom.
class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = globalThis.ResizeObserver ?? (ResizeObserverMock as unknown as typeof ResizeObserver);
