import { ApiError } from './http';
import type { Env } from './types';

export const FAILURE_WINDOW = 15 * 60;
export const FAILURE_LIMIT = 5;
export const REGISTRATION_WINDOW = 60 * 60;
export const REGISTRATION_LIMIT = 30;

interface AttemptRow {
  attempts: string;
  locked_until: number;
}

export function clientIp(request: Request): string {
  // Workers supplies CF-Connecting-IP; never trust a client-provided forwarding chain.
  return request.headers.get('CF-Connecting-IP')?.slice(0, 64) || 'unknown';
}

function rateLimited(retryAfter: number): never {
  const retry_after = Math.max(1, Math.ceil(retryAfter));
  throw new ApiError(429, 'rate_limited', 'Too many attempts. Try again later.', { retry_after }, { 'Retry-After': String(retry_after) });
}

export async function checkLock(env: Env, username: string, ip: string, now: number): Promise<void> {
  const row = await env.DB.prepare('SELECT locked_until FROM auth_attempts WHERE scope = ? AND ip = ?')
    .bind(`u:${username}`, ip).first<{ locked_until: number }>();
  if (row !== null && row.locked_until > now) rateLimited(row.locked_until - now);
}

async function recordAttempt(
  env: Env, scope: string, ip: string, now: number, window: number, limit: number, lockSeconds: number,
): Promise<void> {
  // One conditional UPSERT filters the sliding window, admits the attempt and sets
  // its lock atomically. Arrays stay bounded (5 failures or 30 registrations).
  // D1 has no interactive transaction API: the pre-verification lock read and
  // PBKDF2 cannot be atomic. An already in-flight verification can finish as a
  // lock is established, but its failure cannot bypass/extend that lock. A
  // success only clears unlocked rows. There is no read-modify-write counter.
  const recentCount = '(SELECT count(*) FROM json_each(auth_attempts.attempts) WHERE value > ?)';
  const statement = env.DB.prepare(`
    INSERT INTO auth_attempts (scope, ip, attempts, locked_until, updated_at)
    VALUES (?, ?, json_array(?), 0, ?)
    ON CONFLICT (scope, ip) DO UPDATE SET
      attempts = json_insert((SELECT json_group_array(value) FROM json_each(auth_attempts.attempts) WHERE value > ?), '$[#]', ?),
      locked_until = CASE WHEN ? > 0 AND ${recentCount} + 1 >= ? THEN ? + ? ELSE 0 END,
      updated_at = ?
    WHERE auth_attempts.locked_until <= ? AND (? > 0 OR ${recentCount} < ?)
    RETURNING attempts, locked_until
  `).bind(scope, ip, now, now, now - window, now, lockSeconds, now - window, limit,
    now, lockSeconds, now, now, lockSeconds, now - window, limit);
  const results = await env.DB.batch<AttemptRow>([statement]);
  const row = results[0]?.results[0] ?? null;
  if (row !== null) {
    if (row.locked_until > now) rateLimited(row.locked_until - now);
    return;
  }
  const blocked = await env.DB.prepare('SELECT attempts, locked_until FROM auth_attempts WHERE scope = ? AND ip = ?')
    .bind(scope, ip).first<AttemptRow>();
  if (blocked === null) rateLimited(1);
  if (blocked.locked_until > now) rateLimited(blocked.locked_until - now);
  const timestamps = JSON.parse(blocked.attempts) as number[];
  const oldest = timestamps.filter(time => time > now - window).sort((a, b) => a - b)[0];
  rateLimited(oldest === undefined ? 1 : oldest + window - now);
}

export async function recordFailure(env: Env, username: string, ip: string, now: number): Promise<void> {
  await recordAttempt(env, `u:${username}`, ip, now, FAILURE_WINDOW, FAILURE_LIMIT, FAILURE_WINDOW);
}

export async function clearFailures(env: Env, username: string, ip: string, now: number): Promise<void> {
  await env.DB.batch([env.DB.prepare('DELETE FROM auth_attempts WHERE scope = ? AND ip = ? AND locked_until <= ?')
    .bind(`u:${username}`, ip, now)]);
}

export async function admitRegistration(env: Env, ip: string, now: number): Promise<void> {
  await recordAttempt(env, `i:${ip}`, ip, now, REGISTRATION_WINDOW, REGISTRATION_LIMIT, 0);
}
