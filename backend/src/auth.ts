import { ApiError, invalid, stringField } from './http';
import type { Authenticated, Device, Env, SessionRow, UserRow } from './types';

export const PASSWORD_ITERATIONS = 10_000;
export const SESSION_SECONDS = 180 * 24 * 60 * 60;
export const SLIDE_SECONDS = 60 * 60;

// Common-password denylist; case/NFKC folding here does not alter the stored password.
export const WEAK_PASSWORDS = [
  '123456', 'password', '123456789', '12345', '12345678', 'qwerty', '1234567', '111111',
  '1234567890', '123123', 'abc123', 'password1', '1234', 'iloveyou', '1q2w3e4r', '000000',
  'qwerty123', 'zaq12wsx', 'dragon', 'sunshine', 'princess', 'letmein', '654321', 'monkey',
  '27653', '1qaz2wsx', '123321', 'qwertyuiop', 'superman', 'asdfghjkl', 'admin', 'welcome',
  'football', 'login', 'starwars', 'hello', 'whatever', 'trustno1', 'master', 'freedom',
  '11111111', '7777777', '666666', '121212', '555555', '222222', '444444', '999999',
  '888888', '333333', '777777', '123abc', 'passw0rd', 'password123', 'password1234', 'password12',
  'password01', 'password!', 'password1!', 'password123!', 'p@ssword', 'p@ssw0rd', 'p@ssw0rd!', 'pa55word',
  'qwerty1', 'qwerty12', 'qwerty1234', 'qwerty12345', 'qwerty123456', 'qwerty!', 'qwe123', 'qweasdzxc',
  'asdfgh', 'asdf1234', 'asdfasdf', 'asdfghjk', 'zxcvbnm', 'zxcvbnm123', '1q2w3e', '1q2w3e4r5t',
  '1qazxsw2', 'qazwsx', 'qazwsxedc', 'qazwsx123', 'qazwsx12', 'zaq1xsw2', '!qaz2wsx', '123qwe',
  '123qweasd', '123456a', '123456aa', '123456ab', '123456q', '123456789a', 'a123456', 'a12345678',
  'abc12345', 'abcd1234', 'abcdef', 'abcdefgh', 'abcdefghij', '987654321', '9876543210', '87654321',
  '112233', '11223344', '12344321', '12341234', '123123123', '1234512345', '1234554321', '123456654321',
  '1111111', '111111111', '1111111111', '22222222', '33333333', '44444444', '55555555', '66666666',
  '77777777', '88888888', '99999999', '00000000', '0000000000', '12121212', '10101010', '12340987',
  'admin123', 'admin1234', 'administrator', 'rootroot', 'changeme', 'default', 'welcome1', 'welcome123',
  'welcome!', 'welcome01', 'letmein1', 'letmein123', 'login123', 'test1234', 'testing123', 'testtest',
  'guestguest', 'secret', 'secret123', 'access', 'access123', 'computer', 'computer1', 'internet',
  'internet1', 'security', 'microsoft', 'windows', 'windows1', 'passpass', 'pass1234', 'pass12345',
  'iloveyou1', 'iloveyou123', 'loveyou', 'love1234', 'lovelove', 'beautiful', 'lovely', 'michael',
  'jennifer', 'jessica', 'ashley', 'daniel', 'charlie', 'thomas', 'robert', 'jordan',
  'joshua', 'andrew', 'matthew', 'william', 'anthony', 'alexander', 'christopher', 'michelle',
  'samantha', 'nicole', 'amanda', 'melissa', 'sophie', 'shadow', 'shadow123', 'sunshine1',
  'sunshine123', 'princess1', 'princess123', 'dragon123', 'monkey123', 'superman1', 'batman', 'spiderman',
  'pokemon', 'pokemon123', 'pokemon1', 'football1', 'football123', 'baseball', 'baseball1', 'basketball',
  'basketball1', 'soccer', 'soccer123', 'hockey', 'chelsea', 'liverpool', 'arsenal', 'manchester',
  'manutd', 'barcelona', 'real madrid', 'tiger', 'tigger', 'cookie', 'peanut', 'chocolate',
  'butterfly', 'purple', 'orange', 'silver', 'flower', 'summer', 'summer123', 'winter123',
  'spring123', 'autumn123', 'january', 'september', 'december', 'mustang', 'ferrari', 'mercedes',
  'harley', 'hunter', 'killer', 'thunder', 'scooter', 'diamond', 'ginger', 'pepper',
  'matrix', 'matrix123', 'starwars1', 'starwars123', 'whatever1', 'nothing', 'hello123', 'hello1234',
  'hello12345', 'hellohello', 'helloworld', 'helloworld1', 'goodluck', 'goodluck123', '88888888a', 'woaini123',
  'woaini1314', '5201314', '1314520', 'wang123456', 'zhang123', 'li123456', 'admin888', '8888888888',
] as const;
const weakPasswords: ReadonlySet<string> = new Set(WEAK_PASSWORDS);
const encoder = new TextEncoder();

export function normalizeUsername(value: string): string {
  return value.normalize('NFKC').trim().toLowerCase();
}

export function usernameField(body: Record<string, unknown>): string {
  const username = normalizeUsername(stringField(body, 'username', 1, 256));
  if (!/^[a-z][a-z0-9_]{2,31}$/.test(username)) invalid('username', 'Use 3 to 32 lowercase letters, digits or underscores, starting with a letter.');
  return username;
}

export function passwordField(body: Record<string, unknown>, field: string): string {
  const password = stringField(body, field, 8, 128);
  if (weakPasswords.has(password.normalize('NFKC').toLowerCase())) invalid(field, 'Choose a less common password.');
  return password;
}

export function pepper(env: Env): string {
  if (!env.PEPPER) throw new Error('PEPPER is not configured');
  return env.PEPPER;
}

export function base64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function decodeBase64url(value: string): Uint8Array<ArrayBuffer> {
  const padded = value.replaceAll('-', '+').replaceAll('_', '/') + '='.repeat((4 - value.length % 4) % 4);
  return Uint8Array.from(atob(padded), char => char.charCodeAt(0));
}

export function generateToken(): string {
  return base64url(crypto.getRandomValues(new Uint8Array(32)));
}

export async function sha256Hex(value: string): Promise<string> {
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value)));
  return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
}

async function hmac(env: Env, value: string): Promise<Uint8Array<ArrayBuffer>> {
  const key = await crypto.subtle.importKey('raw', encoder.encode(pepper(env)), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', key, encoder.encode(value)));
}

export function constantTimeEqual(left: string, right: string): boolean {
  const a = encoder.encode(left);
  const b = encoder.encode(right);
  let difference = a.length ^ b.length;
  for (let index = 0; index < Math.max(a.length, b.length); index++) difference |= (a[index] ?? 0) ^ (b[index] ?? 0);
  return difference === 0;
}

async function derivePassword(env: Env, password: string, salt: string, iterations: number): Promise<string> {
  const prehash = await hmac(env, `password:${password}`);
  const key = await crypto.subtle.importKey('raw', prehash, 'PBKDF2', false, ['deriveBits']);
  const derived = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: decodeBase64url(salt), iterations }, key, 256);
  return base64url(new Uint8Array(derived));
}

export async function hashPassword(env: Env, password: string): Promise<{ salt: string; hash: string }> {
  const salt = base64url(crypto.getRandomValues(new Uint8Array(16)));
  return { salt, hash: await derivePassword(env, password, salt, PASSWORD_ITERATIONS) };
}

export async function verifyPassword(env: Env, user: UserRow, password: string): Promise<boolean> {
  return constantTimeEqual(await derivePassword(env, password, user.password_salt, user.password_iterations), user.password_hash);
}

export async function dummyVerify(env: Env, password: string): Promise<boolean> {
  const derived = await derivePassword(env, password, 'AAAAAAAAAAAAAAAAAAAAAA', PASSWORD_ITERATIONS);
  return constantTimeEqual(derived, 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA');
}

export async function recoveryHash(env: Env, code: string): Promise<string> {
  return base64url(await hmac(env, `recovery:${code}`));
}

export async function findUser(env: Env, username: string): Promise<UserRow | null> {
  return env.DB.prepare('SELECT * FROM users WHERE username = ?').bind(username).first<UserRow>();
}

export function publicUser(user: UserRow): { id: string; username: string; displayName: string } {
  return { id: user.id, username: user.username, displayName: user.display_name };
}

export function sessionInvalid(): never {
  throw new ApiError(401, 'session_invalid', 'Session is invalid or expired.');
}

export interface NewSession {
  token: string;
  row: SessionRow;
}

export async function newSession(env: Env, userId: string, device: Device, now: number): Promise<NewSession> {
  const token = generateToken();
  return { token, row: {
    id: crypto.randomUUID(), user_id: userId, token_hash: await sha256Hex(pepper(env) + token),
    device_name: device.deviceName, device_id: device.deviceId, created_at: now, last_used_at: now,
    expires_at: now + SESSION_SECONDS, revoked_at: null, revoke_reason: null,
  } };
}

export function insertSession(env: Env, session: SessionRow, condition: string, bindings: (string | number)[]): D1PreparedStatement {
  return env.DB.prepare(`
    INSERT INTO sessions (id, user_id, token_hash, device_name, device_id, created_at, last_used_at, expires_at)
    SELECT ?, ?, ?, ?, ?, ?, ?, ? WHERE ${condition}
  `).bind(session.id, session.user_id, session.token_hash, session.device_name, session.device_id,
    session.created_at, session.last_used_at, session.expires_at, ...bindings);
}

export async function createSession(env: Env, user: UserRow, device: Device, now: number): Promise<NewSession> {
  const session = await newSession(env, user.id, device, now);
  // A password/recovery mutation winning during verification must prevent old
  // credentials from issuing a new token after all old sessions are revoked.
  const results = await env.DB.batch([insertSession(env, session.row,
    'EXISTS (SELECT 1 FROM users WHERE id = ? AND auth_version = ? AND password_hash = ?)',
    [user.id, user.auth_version, user.password_hash])]);
  if (results[0]?.meta.changes !== 1) throw new ApiError(401, 'invalid_credentials', 'Invalid username or password.');
  return session;
}

export async function authenticate(request: Request, env: Env, now: number): Promise<Authenticated> {
  const authorization = request.headers.get('Authorization');
  const match = authorization?.match(/^Bearer ([A-Za-z0-9_-]{43})$/i);
  const token = match?.[1];
  if (token === undefined) sessionInvalid();
  const tokenHash = await sha256Hex(pepper(env) + token);
  let session = await env.DB.prepare('SELECT * FROM sessions WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?')
    .bind(tokenHash, now).first<SessionRow>();
  if (session === null) sessionInvalid();
  if (now - session.last_used_at >= SLIDE_SECONDS) {
    await env.DB.batch([env.DB.prepare(`UPDATE sessions SET last_used_at = ?, expires_at = ?
      WHERE id = ? AND revoked_at IS NULL AND expires_at > ? AND last_used_at <= ?`)
      .bind(now, now + SESSION_SECONDS, session.id, now, now -SLIDE_SECONDS)]);
    session = await env.DB.prepare('SELECT * FROM sessions WHERE id = ? AND revoked_at IS NULL AND expires_at > ?')
      .bind(session.id, now).first<SessionRow>();
    if (session === null) sessionInvalid();
  }
  const user = await env.DB.prepare('SELECT * FROM users WHERE id = ?').bind(session.user_id).first<UserRow>();
  if (user === null) sessionInvalid();
  return { user, session };
}

export async function revokeCurrent(env: Env, session: SessionRow, now: number): Promise<void> {
  await env.DB.batch([env.DB.prepare("UPDATE sessions SET revoked_at = ?, revoke_reason = 'logout' WHERE id = ? AND user_id = ? AND revoked_at IS NULL")
    .bind(now, session.id, session.user_id)]);
}

export function revokeAll(env: Env, userId: string, now: number, reason: 'password_changed' | 'recovered', guard: string): D1PreparedStatement {
  return env.DB.prepare(`UPDATE sessions SET revoked_at = ?, revoke_reason = ?
    WHERE user_id = ? AND revoked_at IS NULL AND EXISTS (SELECT 1 FROM mutation_guards WHERE id = ?)`)
    .bind(now, reason, userId, guard);
}

interface Mutation {
  user: UserRow;
  now: number;
  recoveryCode?: string;
  password?: { salt: string; hash: string };
  session?: SessionRow;
  device?: Device;
  reason?: 'password_changed' | 'recovered';
}

export async function mutateCredentials(env: Env, mutation: Mutation): Promise<NewSession | null> {
  const { user, now, password, session, device, reason } = mutation;
  const guard = crypto.randomUUID();
  const recoveryCodeHash = mutation.recoveryCode === undefined ? user.recovery_code_hash : await recoveryHash(env, mutation.recoveryCode);
  const nextSession = device === undefined ? null : await newSession(env, user.id, device, now);
  const assignments = password === undefined ? '' : 'password_salt = ?, password_hash = ?, password_iterations = ?, ';
  const passwordBindings = password === undefined ? [] : [password.salt, password.hash, PASSWORD_ITERATIONS];
  const predicate = session === undefined ? 'recovery_code_hash = ?' :
    'EXISTS (SELECT 1 FROM sessions WHERE id = ? AND user_id = users.id AND revoked_at IS NULL AND expires_at > ?)';
  const predicateBindings = session === undefined ? [user.recovery_code_hash] : [session.id, now];
  // D1.batch is atomic even though BEGIN/COMMIT are unavailable. The first UPDATE
  // compares the version/hash read before verification. INSERT ... WHERE changes()
  // carries its result to a unique guard: every dependent mutation is conditional
  // on that guard, and deletion is in the same batch. Recovery competition can
  // therefore have only one winner; losing requests cannot revoke/issue sessions.
  const statements = [
    env.DB.prepare(`UPDATE users SET ${assignments}recovery_code_hash = ?, auth_version = auth_version + 1, updated_at = ?
      WHERE id = ? AND auth_version = ? AND password_hash = ? AND ${predicate}`)
      .bind(...passwordBindings, recoveryCodeHash, now, user.id, user.auth_version, user.password_hash, ...predicateBindings),
    env.DB.prepare('INSERT INTO mutation_guards (id, user_id, created_at) SELECT ?, ?, ? WHERE changes() = 1').bind(guard, user.id, now),
  ];
  if (reason !== undefined) statements.push(revokeAll(env, user.id, now, reason, guard));
  if (nextSession !== null) statements.push(insertSession(env, nextSession.row, 'EXISTS (SELECT 1 FROM mutation_guards WHERE id = ?)', [guard]));
  statements.push(env.DB.prepare('DELETE FROM mutation_guards WHERE id = ?').bind(guard));
  const results = await env.DB.batch(statements);
  if (results[0]?.meta.changes !== 1) {
    if (session !== undefined) sessionInvalid();
    throw new ApiError(401, 'invalid_recovery_code', 'Invalid username or recovery code.');
  }
  return nextSession;
}
