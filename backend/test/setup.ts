import { applyD1Migrations, env } from 'cloudflare:test';
import { beforeAll, beforeEach, vi } from 'vitest';

beforeAll(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

beforeEach(async () => {
  vi.restoreAllMocks();
  // Explicit cleanup also works in pool versions without per-test storage isolation.
  await env.DB.batch([
    env.DB.prepare('DELETE FROM mutation_guards'),
    env.DB.prepare('DELETE FROM practice_segments'),
    env.DB.prepare('DELETE FROM sessions'),
    env.DB.prepare('DELETE FROM users'),
    env.DB.prepare('DELETE FROM auth_attempts'),
  ]);
});
