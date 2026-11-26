import { defineWorkersConfig, readD1Migrations } from '@cloudflare/vitest-pool-workers/config';

export default defineWorkersConfig(async () => ({
  test: {
    poolOptions: {
      workers: {
        wrangler: { configPath: './wrangler.toml' },
        miniflare: {
          compatibilityDate: '2025-06-20',
          bindings: {
            TEST_MIGRATIONS: await readD1Migrations('./migrations'),
            PEPPER: 'test-only-pepper',
          },
        },
      },
    },
    include: ['test/**/*.test.ts'],
    setupFiles: ['./test/setup.ts'],
    fileParallelism: false,
    maxWorkers: 1,
    testTimeout: 30_000,
  },
}));
