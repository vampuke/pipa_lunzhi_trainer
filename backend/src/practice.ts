import { ApiError, deviceIdField, isRecord } from './http';
import type { Env, FieldError } from './types';

export const MAX_SEGMENTS = 500;
export const MAX_BATCH_STATEMENTS = 40;
const DAY_SECONDS = 86_400;
type Source = 'basics' | 'metronome' | 'free';

interface Segment {
  id: string;
  day: string;
  source: Source;
  elapsedMs: number;
}

interface DaySummary {
  day: string;
  basicsMs: number;
  metronomeMs: number;
  freeMs: number;
  totalMs: number;
}

export function dayAt(now: number): string {
  return new Date(now * 1000).toISOString().slice(0, 10);
}

export function validDay(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const timestamp = Date.parse(`${value}T00:00:00.000Z`);
  return Number.isFinite(timestamp) && dayAt(timestamp / 1000) === value;
}

function isSource(value: unknown): value is Source {
  return value === 'basics' || value === 'metronome' || value === 'free';
}

export function validateSegments(body: Record<string, unknown>, now: number): { deviceId: string; segments: Segment[] } {
  const deviceId = deviceIdField(body);
  if (!Array.isArray(body.segments) || body.segments.length > MAX_SEGMENTS) {
    throw new ApiError(400, 'validation_failed', 'Invalid segments.', { errors: [{ field: 'segments', message: 'Must be an array with at most 500 items.' }] });
  }
  const errors: FieldError[] = [];
  const segments: Segment[] = [];
  const from = dayAt(now - 400 * DAY_SECONDS);
  const to = dayAt(now + DAY_SECONDS);
  for (let index = 0; index < body.segments.length; index++) {
    const item: unknown = body.segments[index];
    if (!isRecord(item)) {
      errors.push({ index, field: 'segments', message: 'Must be an object.' });
      continue;
    }
    const { id, day, source, elapsedMs } = item;
    if (typeof id !== 'string' || !/^[\x20-\x7e]{1,160}$/.test(id)) errors.push({ index, field: 'id', message: 'Must be 1 to 160 printable ASCII characters.' });
    if (!validDay(day) || day < from || day > to) errors.push({ index, field: 'day', message: `Must be a real YYYY-MM-DD date from ${from} through ${to}.` });
    if (!isSource(source)) errors.push({ index, field: 'source', message: 'Must be basics, metronome or free.' });
    if (typeof elapsedMs !== 'number' || !Number.isInteger(elapsedMs) || elapsedMs < 1 || elapsedMs > 86_400_000) errors.push({ index, field: 'elapsedMs', message: 'Must be an integer from 1 through 86400000.' });
    if (typeof id === 'string' && typeof day === 'string' && isSource(source) && typeof elapsedMs === 'number') segments.push({ id, day, source, elapsedMs });
  }
  if (errors.length > 0) throw new ApiError(400, 'validation_failed', 'Invalid segments.', { errors });
  return { deviceId, segments };
}

export async function uploadSegments(env: Env, userId: string, body: Record<string, unknown>, now: number): Promise<{ accepted: number; serverTime: number }> {
  // Validate the whole request before writing the first chunk. D1 batches are
  // atomic individually; an infrastructure failure between chunks can leave a
  // prefix saved. Replaying the entire request is safe because the upsert is max.
  const { deviceId, segments } = validateSegments(body, now);
  for (let offset = 0; offset < segments.length; offset += MAX_BATCH_STATEMENTS) {
    const statements = segments.slice(offset, offset + MAX_BATCH_STATEMENTS).map(segment => env.DB.prepare(`
      INSERT INTO practice_segments (user_id, segment_id, day, source, elapsed_ms, device_id, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (user_id, segment_id) DO UPDATE SET
        elapsed_ms = max(elapsed_ms, excluded.elapsed_ms), updated_at = excluded.updated_at
    `).bind(userId, segment.id, segment.day, segment.source, segment.elapsedMs, deviceId, now, now));
    await env.DB.batch(statements);
  }
  return { accepted: segments.length, serverTime: now };
}

export async function summary(env: Env, userId: string, url: URL, now: number): Promise<{
  days: DaySummary[];
  totals: Omit<DaySummary, 'day'>;
  updatedAt: number;
}> {
  const from = url.searchParams.get('from') ?? dayAt(now - 364 * DAY_SECONDS);
  const to = url.searchParams.get('to') ?? dayAt(now);
  const errors: FieldError[] = [];
  if (!validDay(from)) errors.push({ field: 'from', message: 'Must be a real YYYY-MM-DD date.' });
  if (!validDay(to)) errors.push({ field: 'to', message: 'Must be a real YYYY-MM-DD date.' });
  if (errors.length === 0 && from > to) errors.push({ field: 'from', message: 'Must be on or before to.' });
  if (errors.length > 0) throw new ApiError(400, 'validation_failed', 'Invalid summary range.', { errors });
  const results = await env.DB.batch([
    env.DB.prepare(`SELECT day,
      sum(CASE WHEN source = 'basics' THEN elapsed_ms ELSE 0 END) AS basicsMs,
      sum(CASE WHEN source = 'metronome' THEN elapsed_ms ELSE 0 END) AS metronomeMs,
      sum(CASE WHEN source = 'free' THEN elapsed_ms ELSE 0 END) AS freeMs,
      sum(elapsed_ms) AS totalMs
      FROM practice_segments WHERE user_id = ? AND day >= ? AND day <= ? GROUP BY day ORDER BY day`)
      .bind(userId, from, to),
    env.DB.prepare('SELECT max(updated_at) AS updatedAt FROM practice_segments WHERE user_id = ? AND day >= ? AND day <= ?')
      .bind(userId, from, to),
  ]);
  const days = (results[0]?.results ?? []) as unknown as DaySummary[];
  const latest = (results[1]?.results[0] ?? { updatedAt: null }) as { updatedAt: number | null };
  const totals = { basicsMs: 0, metronomeMs: 0, freeMs: 0, totalMs: 0 };
  for (const day of days) {
    totals.basicsMs += day.basicsMs;
    totals.metronomeMs += day.metronomeMs;
    totals.freeMs += day.freeMs;
    totals.totalMs += day.totalMs;
  }
  return { days, totals, updatedAt: latest.updatedAt ?? now };
}
