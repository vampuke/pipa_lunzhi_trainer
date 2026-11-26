import {
  authenticate, constantTimeEqual, createSession, dummyVerify, findUser, generateToken,
  hashPassword, insertSession, mutateCredentials, newSession, PASSWORD_ITERATIONS,
  passwordField, publicUser, recoveryHash, revokeCurrent, usernameField, verifyPassword,
} from './auth';
import { ApiError, deviceFields, json, nowSeconds, readJson, stringField } from './http';
import { admitRegistration, checkLock, clearFailures, clientIp, recordFailure } from './limits';
import { summary, uploadSegments } from './practice';
import type { Env, UserRow } from './types';

const METHODS: Readonly<Record<string, string>> = {
  '/health': 'GET',
  '/api/v1/health': 'GET',
  '/api/v1/auth/register': 'POST',
  '/api/v1/auth/login': 'POST',
  '/api/v1/auth/me': 'GET',
  '/api/v1/auth/logout': 'POST',
  '/api/v1/auth/password': 'POST',
  '/api/v1/auth/recover': 'POST',
  '/api/v1/auth/recovery-code': 'POST',
  '/api/v1/practice/segments': 'POST',
  '/api/v1/practice/summary': 'GET',
};

async function invalidCredentials(env: Env, username: string, ip: string, now: number): Promise<never> {
  await recordFailure(env, username, ip, now);
  throw new ApiError(401, 'invalid_credentials', 'Invalid username or password.');
}

async function verifyCurrentPassword(env: Env, user: UserRow, password: string, ip: string, now: number): Promise<void> {
  await checkLock(env, user.username, ip, now);
  if (!await verifyPassword(env, user, password)) await invalidCredentials(env, user.username, ip, now);
  await clearFailures(env, user.username, ip, now);
}

async function register(request: Request, env: Env, now: number): Promise<Response> {
  const body = await readJson(request);
  const username = usernameField(body);
  const password = passwordField(body, 'password');
  const device = deviceFields(body);
  await admitRegistration(env, clientIp(request), now);
  const credentials = await hashPassword(env, password);
  const recoveryCode = generateToken();
  const user: UserRow = {
    id: crypto.randomUUID(), username, display_name: username, password_algorithm: 'pbkdf2-sha256',
    password_salt: credentials.salt, password_hash: credentials.hash, password_iterations: PASSWORD_ITERATIONS,
    pepper_version: 1, auth_version: 1, recovery_code_hash: await recoveryHash(env, recoveryCode),
    created_at: now, updated_at: now,
  };
  const session = await newSession(env, user.id, device, now);
  const results = await env.DB.batch([
    env.DB.prepare(`INSERT INTO users (id, username, display_name, password_algorithm, password_salt, password_hash,
      password_iterations, pepper_version, auth_version, recovery_code_hash, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (username) DO NOTHING`)
      .bind(user.id, user.username, user.display_name, user.password_algorithm, user.password_salt, user.password_hash,
        user.password_iterations, user.pepper_version, user.auth_version, user.recovery_code_hash, now, now),
    insertSession(env, session.row, 'changes() = 1', []),
  ]);
  if (results[0]?.meta.changes !== 1) throw new ApiError(409, 'username_taken', 'Username is already registered.');
  return json({ token: session.token, expiresAt: session.row.expires_at, user: publicUser(user), recoveryCode }, 201);
}

async function login(request: Request, env: Env, now: number): Promise<Response> {
  const body = await readJson(request);
  const username = usernameField(body);
  const password = stringField(body, 'password', 1, 128);
  const device = deviceFields(body);
  const ip = clientIp(request);
  await checkLock(env, username, ip, now);
  const user = await findUser(env, username);
  const valid = user === null ? await dummyVerify(env, password) : await verifyPassword(env, user, password);
  if (user === null || !valid) return invalidCredentials(env, username, ip, now);
  await clearFailures(env, username, ip, now);
  const session = await createSession(env, user, device, now);
  return json({ token: session.token, expiresAt: session.row.expires_at, user: publicUser(user) });
}

async function recover(request: Request, env: Env, now: number): Promise<Response> {
  const body = await readJson(request);
  const username = usernameField(body);
  const code = stringField(body, 'recoveryCode', 1, 128).trim();
  const password = passwordField(body, 'newPassword');
  const device = deviceFields(body);
  const ip = clientIp(request);
  await checkLock(env, username, ip, now);
  const user = await findUser(env, username);
  // Run the same PBKDF2 workload for known and unknown recovery usernames.
  await dummyVerify(env, code);
  const hash = await recoveryHash(env, code);
  const valid = constantTimeEqual(hash, user?.recovery_code_hash ?? 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA');
  if (user === null || !valid) {
    await recordFailure(env, username, ip, now);
    throw new ApiError(401, 'invalid_recovery_code', 'Invalid username or recovery code.');
  }
  const recoveryCode = generateToken();
  const session = await mutateCredentials(env, {
    user, now, recoveryCode, password: await hashPassword(env, password), device, reason: 'recovered',
  });
  if (session === null) throw new Error('Recovery did not create a session');
  await clearFailures(env, username, ip, now);
  return json({ token: session.token, expiresAt: session.row.expires_at, user: publicUser(user), recoveryCode });
}

export async function route(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const method = Object.hasOwn(METHODS, url.pathname) ? METHODS[url.pathname] : undefined;
  if (method === undefined) throw new ApiError(404, 'not_found', 'Resource not found.');
  if (request.method !== method) throw new ApiError(405, 'method_not_allowed', 'Method not allowed.', {}, { Allow: method });
  const now = nowSeconds();
  switch (url.pathname) {
    case '/health':
    case '/api/v1/health': return json({ ok: true, apiVersion: 1 });
    case '/api/v1/auth/register': return register(request, env, now);
    case '/api/v1/auth/login': return login(request, env, now);
    case '/api/v1/auth/recover': return recover(request, env, now);
  }
  const auth = await authenticate(request, env, now);
  switch (url.pathname) {
    case '/api/v1/auth/me':
      return json({ user: publicUser(auth.user), expiresAt: auth.session.expires_at,
        session: { id: auth.session.id, createdAt: auth.session.created_at, lastUsedAt: auth.session.last_used_at } });
    case '/api/v1/auth/logout':
      await revokeCurrent(env, auth.session, now);
      return new Response(null, { status: 204, headers: { 'Cache-Control': 'no-store' } });
    case '/api/v1/auth/password': {
      const body = await readJson(request);
      const currentPassword = stringField(body, 'currentPassword', 1, 128);
      const newPassword = passwordField(body, 'newPassword');
      await verifyCurrentPassword(env, auth.user, currentPassword, clientIp(request), now);
      const session = await mutateCredentials(env, {
        user: auth.user, now, session: auth.session, password: await hashPassword(env, newPassword),
        device: { deviceName: auth.session.device_name, deviceId: auth.session.device_id }, reason: 'password_changed',
      });
      if (session === null) throw new Error('Password change did not create a session');
      return json({ token: session.token, expiresAt: session.row.expires_at });
    }
    case '/api/v1/auth/recovery-code': {
      const body = await readJson(request);
      const password = stringField(body, 'password', 1, 128);
      await verifyCurrentPassword(env, auth.user, password, clientIp(request), now);
      const recoveryCode = generateToken();
      await mutateCredentials(env, { user: auth.user, now, session: auth.session, recoveryCode });
      return json({ recoveryCode });
    }
    case '/api/v1/practice/segments':
      return json(await uploadSegments(env, auth.user.id, await readJson(request), now));
    case '/api/v1/practice/summary':
      return json(await summary(env, auth.user.id, url, now));
    default: throw new ApiError(404, 'not_found', 'Resource not found.');
  }
}
