import { cloudflareTest, readD1Migrations } from '@cloudflare/vitest-pool-workers';
import { defineConfig } from 'vitest/config';

const migrations = await readD1Migrations('./migrations');

export default defineConfig({
  plugins: [cloudflareTest({
    wrangler: { configPath: './wrangler.toml' },
    remoteBindings: false,
    miniflare: {
      d1Databases: ['DB'],
      bindings: { PEPPER: 'test-only-pepper', TEST_MIGRATIONS: migrations },
    },
  })],
  test: {
    include: ['test/**/*.test.ts'],
    setupFiles: ['./test/setup.ts'],
    fileParallelism: false,
    maxWorkers: 1,
    testTimeout: 30_000,
  },
});
