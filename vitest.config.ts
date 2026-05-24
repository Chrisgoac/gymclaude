import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

const alias = { '@': fileURLToPath(new URL('./', import.meta.url)) };

// node_modules tests excluded by vitest's configDefaults.exclude
const defaultExclude = ['**/node_modules/**', '**/.git/**'];

export default defineConfig({
  plugins: [react()],
  resolve: { alias },
  test: {
    globals: true,
    projects: [
      {
        plugins: [react()],
        test: {
          name: 'api',
          include: ['app/api/**/*.test.ts'],
          exclude: defaultExclude,
          environment: 'node',
          globals: true,
        },
        resolve: { alias },
      },
      {
        plugins: [react()],
        test: {
          name: 'app',
          include: ['**/*.{test,spec}.?(c|m)[jt]s?(x)'],
          exclude: [...defaultExclude, 'app/api/**/*.test.ts'],
          environment: 'jsdom',
          globals: true,
          setupFiles: ['./vitest.setup.ts'],
        },
        resolve: { alias },
      },
    ],
  },
});
