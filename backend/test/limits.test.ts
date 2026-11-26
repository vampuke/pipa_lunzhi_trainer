import { env } from 'cloudflare:test';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clientIp, FAILURE_WINDOW, REGISTRATION_WINDOW } from '../src/limits';
import { call, DEVICE, NEW_PASSWORD, NOW, PASSWORD, register } from './helpers';

beforeEach(() => { vi.spyOn(Date, 'now').mockReturnValue(NOW * 1000); });

async function fail(username = 'pipa_user', ip = '192.0.2.10') {
  return call('/auth/login', { body: { username, password: 'Incorrect-739!', ...DEVICE }, ip });
}

describe('D1 sliding rate limits', () => {
  it('locks on the fifth failure, checks before correct verification, and unlocks after 15 minutes', async () => {
    await register();
    for (let index = 0; index < 4; index++) expect((await fail()).status).toBe(401);
    const fifth = await fail();
    expect(fifth.status).toBe(429);
    expect(fifth.body.error.code).toBe('rate_limited');
    expect(fifth.body.retry_after).toBe(900);
    expect(fifth.headers.get('Retry-After')).toBe('900');
    const derive = vi.spyOn(crypto.subtle, 'deriveBits');
    const correct = await call('/auth/login', { body: { username: 'pipa_user', password: PASSWORD, ...DEVICE } });
    expect(correct.status).toBe(429);
    expect(correct.body).toEqual(fifth.body);
    expect(derive).not.toHaveBeenCalled();
    vi.mocked(Date.now).mockReturnValue((NOW + FAILURE_WINDOW - 1) * 1000);
    expect((await fail()).body.retry_after).toBe(1);
    vi.mocked(Date.now).mockReturnValue((NOW + FAILURE_WINDOW) * 1000);
    expect((await call('/auth/login', { body: { username: 'pipa_user', password: PASSWORD, ...DEVICE } })).status).toBe(200);
  });

  it('returns identical locks for unknown usernames and includes recovery in the same lock', async () => {
    await register();
    for (const username of ['pipa_user', 'unknown_user']) for (let index = 0; index < 5; index++) await fail(username);
    const known = await call('/auth/login', { body: { username: 'pipa_user', password: PASSWORD, ...DEVICE } });
    const unknown = await call('/auth/login', { body: { username: 'unknown_user', password: PASSWORD, ...DEVICE } });
    expect(known.status).toBe(429);
    expect(unknown.status).toBe(known.status);
    expect(unknown.body).toEqual(known.body);
    const recovery = await call('/auth/recover', { body: { username: 'pipa_user', recoveryCode: 'bad-code', newPassword: NEW_PASSWORD, ...DEVICE } });
    expect(recovery.status).toBe(429);
    expect(recovery.body).toEqual(known.body);
  });

  it('isolates username/IP pairs and successful login clears only unlocked failures', async () => {
    await register();
    for (let index = 0; index < 5; index++) await fail();
    expect((await call('/auth/login', { body: { username: 'pipa_user', password: PASSWORD, ...DEVICE }, ip: '192.0.2.11' })).status).toBe(200);
    expect((await fail('other_user')).status).toBe(401);
    expect((await fail()).status).toBe(429);
    for (let index = 0; index < 4; index++) await fail('pipa_user', '192.0.2.12');
    expect((await call('/auth/login', { body: { username: 'pipa_user', password: PASSWORD, ...DEVICE }, ip: '192.0.2.12' })).status).toBe(200);
    expect((await fail('pipa_user', '192.0.2.12')).status).toBe(401);
  });

  it('filters old timestamps rather than resetting a fixed window', async () => {
    await register();
    expect((await fail()).status).toBe(401);
    vi.mocked(Date.now).mockReturnValue((NOW + 800) * 1000);
    for (let index = 0; index < 3; index++) expect((await fail()).status).toBe(401);
    vi.mocked(Date.now).mockReturnValue((NOW + 901) * 1000);
    expect((await fail()).status).toBe(401); // The initial failure is outside the window.
    expect((await fail()).status).toBe(429);
  });

  it('does not lose increments during concurrent failures or extend locks on blocked attempts', async () => {
    await register();
    const responses = await Promise.all(Array.from({ length: 8 }, () => fail()));
    expect(responses.filter(response => response.status === 401)).toHaveLength(4);
    expect(responses.filter(response => response.status === 429)).toHaveLength(4);
    const row = await env.DB.prepare('SELECT attempts, locked_until FROM auth_attempts WHERE scope = ? AND ip = ?')
      .bind('u:pipa_user', '192.0.2.10').first<{ attempts: string; locked_until: number }>();
    expect(JSON.parse(row?.attempts ?? '[]')).toHaveLength(5);
    expect(row?.locked_until).toBe(NOW + FAILURE_WINDOW);
  });

  it('limits registrations to 30 per IP in a sliding hour with a bounded retry time', async () => {
    const responses = await Promise.all(Array.from({ length: 31 }, (_, index) => call('/auth/register', {
      body: { username: `pipa_${index}`, password: PASSWORD, ...DEVICE },
    })));
    expect(responses.filter(response => response.status === 201)).toHaveLength(30);
    const limited = responses.find(response => response.status === 429);
    expect(limited?.body.retry_after).toBe(REGISTRATION_WINDOW);
    expect(limited?.headers.get('Retry-After')).toBe(String(REGISTRATION_WINDOW));
    expect((await call('/auth/register', { body: { username: 'different_ip', password: PASSWORD, ...DEVICE }, ip: '192.0.2.11' })).status).toBe(201);
    vi.mocked(Date.now).mockReturnValue((NOW + REGISTRATION_WINDOW) * 1000);
    expect((await call('/auth/register', { body: { username: 'after_window', password: PASSWORD, ...DEVICE } })).status).toBe(201);
  });

  it('uses the trusted connection IP and falls back to a shared unknown scope locally', async () => {
    expect(clientIp(new Request('https://pipa-api.test'))).toBe('unknown');
    const requestHeaders = { 'X-Forwarded-For': '192.0.2.200' };
    await register();
    for (let index = 0; index < 5; index++) await call('/auth/login', { body: { username: 'pipa_user', password: 'Incorrect-739!', ...DEVICE }, headers: requestHeaders });
    expect((await call('/auth/login', { body: { username: 'pipa_user', password: PASSWORD, ...DEVICE }, headers: { 'X-Forwarded-For': '192.0.2.201' } })).status).toBe(429);
  });

  it('applies failure locks to authenticated password changes and recovery-code regeneration', async () => {
    const account = await register();
    for (let index = 0; index < 5; index++) await call('/auth/recovery-code', { token: account.token, body: { password: 'Incorrect-739!' } });
    expect((await call('/auth/recovery-code', { token: account.token, body: { password: PASSWORD } })).status).toBe(429);
    expect((await call('/auth/password', { token: account.token, body: { currentPassword: PASSWORD, newPassword: NEW_PASSWORD } })).status).toBe(429);
    expect((await call('/auth/me', { token: account.token })).status).toBe(200);
  });
});
