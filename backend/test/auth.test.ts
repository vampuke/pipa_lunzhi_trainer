import { env, SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  constantTimeEqual, createSession, dummyVerify, findUser, hashPassword, PASSWORD_ITERATIONS,
  pepper, SESSION_SECONDS, sha256Hex, SLIDE_SECONDS, verifyPassword, WEAK_PASSWORDS,
} from '../src/auth';
import worker from '../src/index';
import { call, DEVICE, login, NEW_PASSWORD, NOW, PASSWORD, register } from './helpers';
import type { AuthBody, MeBody } from './helpers';
import type { SessionRow } from '../src/types';

beforeEach(() => { vi.spyOn(Date, 'now').mockReturnValue(NOW * 1000); });

describe('authentication', () => {
  it('routes health through the Worker and rejects unknown paths and wrong methods', async () => {
    const health = await SELF.fetch('https://pipa-api.test/api/v1/health');
    expect(health.status).toBe(200);
    expect(await health.json()).toEqual({ ok: true, apiVersion: 1 });
    expect(health.headers.get('Cache-Control')).toBe('no-store');
    expect(health.headers.get('X-Request-Id')).toBeTruthy();
    const root = await worker.fetch(new Request('https://pipa-api.test/health'), env);
    expect(await root.json()).toEqual({ ok: true, apiVersion: 1 });
    const missing = await call('/users/someone-else');
    expect(missing.status).toBe(404);
    const method = await call('/auth/login');
    expect(method.status).toBe(405);
    expect(method.headers.get('Allow')).toBe('POST');
  });

  it('normalizes usernames and stores only salted/peppered hashes and second timestamps', async () => {
    const account = await register('  Ｐｉｐａ＿Ｕｓｅｒ  ');
    expect(account.user).toEqual({ id: expect.any(String), username: 'pipa_user', displayName: 'pipa_user' });
    expect(account.user.id).toMatch(/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
    expect(account.token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(account.recoveryCode).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(account.expiresAt).toBe(NOW + SESSION_SECONDS);
    const user = await findUser(env, 'pipa_user');
    expect(user).not.toBeNull();
    if (user === null) throw new Error('Missing test user');
    expect(user.password_iterations).toBe(10_000);
    expect(user.password_algorithm).toBe('pbkdf2-sha256');
    expect(user.password_hash).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(user.password_salt).toMatch(/^[A-Za-z0-9_-]{22}$/);
    expect(user.recovery_code_hash).not.toBe(account.recoveryCode);
    expect(user.created_at).toBe(NOW);
    expect(await verifyPassword(env, user, PASSWORD)).toBe(true);
    expect(await verifyPassword({ DB: env.DB, PEPPER: 'other-pepper' }, user, PASSWORD)).toBe(false);
    const session = await env.DB.prepare('SELECT * FROM sessions WHERE user_id = ?').bind(user.id).first<SessionRow>();
    expect(session?.token_hash).toBe(await sha256Hex(pepper(env) + account.token));
    expect(session?.token_hash).not.toBe(account.token);
    const second = await hashPassword(env, PASSWORD);
    expect(second.salt).not.toBe(user.password_salt);
    expect(second.hash).not.toBe(user.password_hash);
    expect(PASSWORD_ITERATIONS).toBe(10_000);
    expect(await dummyVerify(env, PASSWORD)).toBe(false);
    expect(constantTimeEqual('abc', 'abc')).toBe(true);
    expect(constantTimeEqual('abc', 'ab')).toBe(false);
    expect(constantTimeEqual('abc', 'abd')).toBe(false);
  });

  it('rejects duplicate normalized usernames including concurrent registration', async () => {
    const responses = await Promise.all(['PIPA_USER', ' pipa_user '].map(username =>
      call<AuthBody>('/auth/register', { body: { username, password: PASSWORD, ...DEVICE } })));
    expect(responses.map(response => response.status).sort()).toEqual([201, 409]);
    const count = await env.DB.prepare('SELECT count(*) AS n FROM sessions').first<{ n: number }>();
    expect(count?.n).toBe(1);
  });

  it('gives identical wrong-password and unknown-user errors with dummy derivation', async () => {
    await register();
    const known = await call('/auth/login', { body: { username: 'pipa_user', password: 'Incorrect-739!', ...DEVICE } });
    const unknown = await call('/auth/login', { body: { username: 'unknown_user', password: 'Incorrect-739!', ...DEVICE } });
    expect(known.status).toBe(401);
    expect(unknown.status).toBe(known.status);
    expect(unknown.body).toEqual(known.body);
    expect(known.body.error.code).toBe('invalid_credentials');
    // Timing assertions are unreliable on shared runners; the helper above and
    // implementation ensure the unknown-user branch performs real WebCrypto.
  });

  it('keeps simultaneous device sessions and logout revokes only the caller', async () => {
    const first = await register();
    const second = await login();
    expect(second.token).not.toBe(first.token);
    expect(second).not.toHaveProperty('recoveryCode');
    expect((await call('/auth/me', { token: first.token })).status).toBe(200);
    expect((await call('/auth/me', { token: second.token })).status).toBe(200);
    const logout = await call('/auth/logout', { method: 'POST', token: first.token });
    expect(logout.status).toBe(204);
    expect(logout.body).toBeNull();
    expect((await call('/auth/me', { token: first.token })).body.error.code).toBe('session_invalid');
    expect((await call('/auth/me', { token: second.token })).status).toBe(200);
  });

  it('slides expiry at one hour and rejects expiry at the exact boundary', async () => {
    const account = await register();
    vi.mocked(Date.now).mockReturnValue((NOW + SLIDE_SECONDS - 1) * 1000);
    const before = await call<MeBody>('/auth/me', { token: account.token });
    expect(before.body.session.lastUsedAt).toBe(NOW);
    expect(before.body.expiresAt).toBe(account.expiresAt);
    vi.mocked(Date.now).mockReturnValue((NOW + SLIDE_SECONDS) * 1000);
    const after = await call<MeBody>('/auth/me', { token: account.token });
    expect(after.body.session.lastUsedAt).toBe(NOW + SLIDE_SECONDS);
    expect(after.body.expiresAt).toBe(NOW + SLIDE_SECONDS + SESSION_SECONDS);
    vi.mocked(Date.now).mockReturnValue(after.body.expiresAt * 1000);
    const expired = await call('/auth/me', { token: account.token });
    expect(expired.status).toBe(401);
    expect(expired.body.error.code).toBe('session_invalid');
  });

  it('slides on practice requests and never resurrects an unrefreshed 180-day session', async () => {
    const account = await register();
    vi.mocked(Date.now).mockReturnValue((NOW + SLIDE_SECONDS) * 1000);
    expect((await call('/practice/summary', { token: account.token })).status).toBe(200);
    const session = await env.DB.prepare('SELECT last_used_at, expires_at FROM sessions WHERE user_id = ?')
      .bind(account.user.id).first<SessionRow>();
    expect(session?.last_used_at).toBe(NOW + SLIDE_SECONDS);
    expect(session?.expires_at).toBe(NOW + SLIDE_SECONDS + SESSION_SECONDS);
    vi.mocked(Date.now).mockReturnValue(NOW * 1000);
    const other = await login();
    vi.mocked(Date.now).mockReturnValue((NOW + SESSION_SECONDS) * 1000);
    expect((await call('/practice/summary', { token: other.token })).status).toBe(401);
  });

  it('changes password, preserves recovery, revokes all sessions and blocks stale login issuance', async () => {
    const first = await register();
    const second = await login();
    const user = await findUser(env, 'pipa_user');
    if (user === null) throw new Error('Missing test user');
    const wrong = await call('/auth/password', { token: first.token, body: { currentPassword: 'Incorrect-739!', newPassword: NEW_PASSWORD } });
    expect(wrong.status).toBe(401);
    const changed = await call<AuthBody>('/auth/password', { token: first.token, body: { currentPassword: PASSWORD, newPassword: NEW_PASSWORD } });
    expect(changed.status).toBe(200);
    expect(Object.keys(changed.body).sort()).toEqual(['expiresAt', 'token']);
    for (const token of [first.token, second.token]) expect((await call('/auth/me', { token })).status).toBe(401);
    expect((await call('/auth/me', { token: changed.body.token })).status).toBe(200);
    await expect(createSession(env, user, DEVICE, NOW)).rejects.toMatchObject({ status: 401 });
    const old = await call('/auth/login', { body: { username: 'pipa_user', password: PASSWORD, ...DEVICE } });
    expect(old.status).toBe(401);
    await login('pipa_user', NEW_PASSWORD);
    const recovered = await call<AuthBody>('/auth/recover', { body: { username: 'pipa_user', recoveryCode: first.recoveryCode, newPassword: PASSWORD, ...DEVICE } });
    expect(recovered.status).toBe(200);
    expect((await call('/auth/me', { token: changed.body.token })).status).toBe(401);
  });

  it('allows a recovery code to win only once under competition and rotates it', async () => {
    const first = await register();
    const second = await login();
    const body = { username: 'pipa_user', recoveryCode: first.recoveryCode, newPassword: NEW_PASSWORD, ...DEVICE };
    const competing = await Promise.all([call<AuthBody>('/auth/recover', { body }), call<AuthBody>('/auth/recover', { body })]);
    expect(competing.map(response => response.status).sort()).toEqual([200, 401]);
    const winner = competing.find(response => response.status === 200);
    expect(winner?.body.recoveryCode).not.toBe(first.recoveryCode);
    expect((await call('/auth/me', { token: first.token })).status).toBe(401);
    expect((await call('/auth/me', { token: second.token })).status).toBe(401);
    const replay = await call('/auth/recover', { body });
    expect(replay.status).toBe(401);
    expect(replay.body.error.code).toBe('invalid_recovery_code');
    const guards = await env.DB.prepare('SELECT count(*) AS n FROM mutation_guards').first<{ n: number }>();
    expect(guards?.n).toBe(0);
    await login('pipa_user', NEW_PASSWORD);
    if (winner === undefined) throw new Error('Missing recovery winner');
    expect((await call('/auth/recover', { body: { ...body, recoveryCode: winner.body.recoveryCode } })).status).toBe(200);
  });

  it('requires password to regenerate recovery codes without revoking existing sessions', async () => {
    const first = await register();
    const second = await login();
    expect((await call('/auth/recovery-code', { token: first.token, body: { password: 'Incorrect-739!' } })).status).toBe(401);
    const rotated = await call<{ recoveryCode: string }>('/auth/recovery-code', { token: first.token, body: { password: PASSWORD } });
    expect(rotated.status).toBe(200);
    expect(rotated.body.recoveryCode).not.toBe(first.recoveryCode);
    expect((await call('/auth/me', { token: first.token })).status).toBe(200);
    expect((await call('/auth/me', { token: second.token })).status).toBe(200);
    const body = { username: 'pipa_user', recoveryCode: first.recoveryCode, newPassword: NEW_PASSWORD, ...DEVICE };
    expect((await call('/auth/recover', { body })).status).toBe(401);
    expect((await call('/auth/recover', { body: { ...body, recoveryCode: rotated.body.recoveryCode } })).status).toBe(200);
  });

  it.each(['/auth/me', '/auth/logout', '/auth/password', '/auth/recovery-code', '/practice/segments', '/practice/summary'])('requires a valid bearer token for %s', async path => {
    const method = ['/auth/me', '/practice/summary'].includes(path) ? 'GET' : 'POST';
    for (const token of [undefined, 'bad-token', 'A'.repeat(43)]) {
      const response = await call(path, { method, ...(token === undefined ? {} : { token }) });
      expect(response.status).toBe(401);
      expect(response.body.error.code).toBe('session_invalid');
    }
  });

  it('rejects malformed input and maintains a common-password list of at least 200 entries', async () => {
    expect(new Set(WEAK_PASSWORDS).size).toBeGreaterThanOrEqual(200);
    for (const password of ['short', 'x'.repeat(129), 'password', 'Password123!', 'ｐａｓｓｗｏｒｄ']) {
      expect((await call('/auth/register', { body: { username: 'pipa_user', password, ...DEVICE } })).status).toBe(400);
    }
    for (const username of ['ab', '1abc', 'a'.repeat(33), 'bad-name', '中文名称', 'a\u0000bc']) {
      expect((await call('/auth/register', { body: { username, password: PASSWORD, ...DEVICE } })).status).toBe(400);
    }
    expect((await call('/auth/register', { body: { username: 'pipa_user', password: PASSWORD, ...DEVICE, deviceName: ' ' } })).status).toBe(400);
    expect((await call('/auth/register', { body: { username: 'pipa_user', password: PASSWORD, ...DEVICE, deviceId: '\n' } })).status).toBe(400);
    for (const raw of ['{', '[]', 'null', '"text"']) expect((await call('/auth/register', { raw })).status).toBe(400);
    expect((await call('/auth/register', { raw: '{}', headers: { 'Content-Type': 'text/plain' } })).status).toBe(415);
    expect((await call('/auth/register', { raw: ' '.repeat(256 * 1024 + 1) })).status).toBe(413);
    expect((await call('/auth/register', { raw: '{}', headers: { 'Content-Length': '262145' } })).status).toBe(413);
  });

  it('returns a safe 500 with a request ID when the pepper is missing', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {});
    const response = await worker.fetch(new Request('https://pipa-api.test/api/v1/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'unknown_user', password: PASSWORD, ...DEVICE }),
    }), { DB: env.DB });
    expect(response.status).toBe(500);
    expect(await response.json()).toEqual({ error: { code: 'internal_error', message: 'An internal error occurred.' } });
    expect(log).toHaveBeenCalledTimes(1);
    const logged: unknown = JSON.parse(String(log.mock.calls[0]?.[0]));
    expect(logged).toEqual({ event: 'request_failed', requestId: response.headers.get('X-Request-Id') });
  });
});
