import { env } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';
import { NOW, register } from './helpers';

type SqlValue = string | number | null;

const fixtures: Record<string, Record<string, SqlValue>> = {
  users: {
    id: 'bad-user', username: 'valid_user', display_name: 'Valid user', password_algorithm: 'pbkdf2-sha256',
    password_salt: 'A'.repeat(22), password_hash: 'A'.repeat(43), password_iterations: 10000,
    pepper_version: 1, auth_version: 1, recovery_code_hash: 'A'.repeat(43), created_at: NOW, updated_at: NOW,
  },
  sessions: {
    id: 'bad-session', user_id: '', token_hash: 'a'.repeat(64), device_name: 'Phone', device_id: 'device',
    created_at: NOW, last_used_at: NOW, expires_at: NOW + 1, revoked_at: null, revoke_reason: null,
  },
  practice_segments: {
    user_id: '', segment_id: 'segment', day: '2026-11-26', source: 'basics', elapsed_ms: 1000,
    device_id: 'device', created_at: NOW, updated_at: NOW,
  },
  auth_attempts: { scope: 'u:valid_user', ip: '192.0.2.10', attempts: '[1]', locked_until: 0, updated_at: NOW },
  mutation_guards: { id: 'guard', user_id: '', created_at: NOW },
};

const invalidRows: [string, string, SqlValue][] = [
  ['users', 'username', 'ab'], ['users', 'username', 'a'.repeat(33)], ['users', 'username', 'Uppercase'],
  ['users', 'username', '1bad'], ['users', 'username', 'bad-name'], ['users', 'username', 'abc\0bad'],
  ['users', 'display_name', ''], ['users', 'password_algorithm', 'sha256'],
  ['users', 'password_salt', 'A'.repeat(21)], ['users', 'password_salt', '+'.repeat(22)],
  ['users', 'password_hash', 'A'.repeat(42)], ['users', 'password_hash', '+'.repeat(43)],
  ['users', 'password_iterations', 9999], ['users', 'password_iterations', 10000.5],
  ['users', 'pepper_version', 2], ['users', 'auth_version', 0], ['users', 'auth_version', 1.5],
  ['users', 'recovery_code_hash', 'A'.repeat(42)], ['users', 'recovery_code_hash', '='.repeat(43)],
  ['users', 'created_at', -1], ['users', 'created_at', 1.5], ['users', 'updated_at', NOW - 1],
  ['sessions', 'token_hash', 'a'.repeat(63)], ['sessions', 'token_hash', 'A'.repeat(64)],
  ['sessions', 'device_name', ''], ['sessions', 'device_id', ''], ['sessions', 'created_at', -1],
  ['sessions', 'last_used_at', NOW - 1], ['sessions', 'expires_at', NOW],
  ['sessions', 'revoked_at', NOW - 1], ['sessions', 'revoke_reason', 'bad'],
  ['practice_segments', 'segment_id', ''], ['practice_segments', 'segment_id', 'x'.repeat(161)],
  ['practice_segments', 'segment_id', 'bad\n'], ['practice_segments', 'segment_id', 'abc\0bad'],
  ['practice_segments', 'day', '2026-1-01'], ['practice_segments', 'day', '2026-02-29'],
  ['practice_segments', 'day', '2026-02-30'], ['practice_segments', 'day', '2026-13-01'],
  ['practice_segments', 'source', 'other'], ['practice_segments', 'elapsed_ms', 0],
  ['practice_segments', 'elapsed_ms', 86400001], ['practice_segments', 'elapsed_ms', 1.5],
  ['practice_segments', 'device_id', ''], ['practice_segments', 'created_at', -1], ['practice_segments', 'updated_at', NOW - 1],
  ['auth_attempts', 'scope', 'bad'], ['auth_attempts', 'ip', ''], ['auth_attempts', 'attempts', '{}'],
  ['auth_attempts', 'attempts', '[]'], ['auth_attempts', 'attempts', JSON.stringify(Array.from({ length: 31 }, () => 1))],
  ['auth_attempts', 'locked_until', -1], ['auth_attempts', 'updated_at', -1], ['mutation_guards', 'created_at', -1],
];

function insert(table: string, row: Record<string, SqlValue>): D1PreparedStatement {
  const columns = Object.keys(row);
  return env.DB.prepare(`INSERT INTO ${table} (${columns.join(', ')}) VALUES (${columns.map(() => '?').join(', ')})`)
    .bind(...Object.values(row));
}

describe('0001_init.sql applied through the pool D1 binding', () => {
  it('applies the actual migration and defines every table and required index', async () => {
    expect(env.TEST_MIGRATIONS[0]?.name).toBe('0001_init.sql');
    const tables = await env.DB.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all<{ name: string }>();
    for (const table of Object.keys(fixtures)) expect(tables.results.map(row => row.name)).toContain(table);
    const indexes = await env.DB.prepare("SELECT name FROM sqlite_master WHERE type = 'index'").all<{ name: string }>();
    expect(indexes.results.map(row => row.name)).toContain('practice_segments_user_day');
    const foreignKeys = await env.DB.prepare('PRAGMA foreign_keys').first<{ foreign_keys: number }>();
    expect(foreignKeys?.foreign_keys).toBe(1);
  });

  it.each(invalidRows)('rejects CHECK violation %s.%s = %s', async (table, field, value) => {
    const account = await register('fixture_user');
    const fixture = fixtures[table];
    if (fixture === undefined) throw new Error('Unknown fixture');
    const row = { ...fixture, ...(Object.hasOwn(fixture, 'user_id') ? { user_id: account.user.id } : {}), [field]: value };
    await expect(insert(table, row).run()).rejects.toThrow(/constraint/i);
  });

  it('enforces username/token uniqueness and foreign keys without FTS', async () => {
    const account = await register('fixture_user');
    const user = fixtures.users;
    const session = fixtures.sessions;
    const segment = fixtures.practice_segments;
    if (user === undefined || session === undefined || segment === undefined) throw new Error('Missing fixtures');
    await expect(insert('users', { ...user, username: 'fixture_user' }).run()).rejects.toThrow(/unique/i);
    await insert('sessions', { ...session, id: 'one', user_id: account.user.id }).run();
    await expect(insert('sessions', { ...session, id: 'two', user_id: account.user.id }).run()).rejects.toThrow(/unique/i);
    await expect(insert('practice_segments', { ...segment, user_id: 'missing-user' }).run()).rejects.toThrow(/foreign key/i);
    const fts = await env.DB.prepare("SELECT name FROM sqlite_master WHERE sql LIKE '%VIRTUAL TABLE%'").all();
    expect(fts.results).toHaveLength(0);
  });
});
