import path from 'node:path';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    setupFiles: ['./tests/setup.ts'],
    include: ['apps/*/tests/**/*.test.ts'],
    testTimeout: 30_000,
    hookTimeout: 30_000,
    bail: 1,
    fileParallelism: false,
  },
  resolve: {
    alias: {
      '@webhook': path.resolve(__dirname, './apps/webhook/src'),
      '@webhook-tests': path.resolve(__dirname, './apps/webhook/tests'),
      '@test-support': path.resolve(__dirname, './tests'),
    },
  },
});
