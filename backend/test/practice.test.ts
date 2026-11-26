import { env } from 'cloudflare:test';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { dayAt } from '../src/practice';
import { call, DEVICE, NOW, register } from './helpers';
import type { SummaryBody } from './helpers';

const segment = { id: 'd:test-device-1:1_2:0:2026-11-26', day: '2026-11-26', source: 'basics', elapsedMs: 1000 };
beforeEach(() => { vi.spyOn(Date, 'now').mockReturnValue(NOW * 1000); });

describe('practice synchronization', () => {
  it('aggregates all sources across days and devices, takes max on replay and keeps original metadata', async () => {
    const user = await register();
    const segments = [segment, { ...segment, id: 'metro', source: 'metronome', elapsedMs: 2000 },
      { ...segment, id: 'free', source: 'free', elapsedMs: 3000 },
      { ...segment, id: 'yesterday', day: '2026-11-25', elapsedMs: 4000 }];
    const uploaded = await call<{ accepted: number; serverTime: number }>('/practice/segments', { token: user.token, body: { deviceId: DEVICE.deviceId, segments } });
    expect(uploaded.status).toBe(200);
    expect(uploaded.body).toEqual({ accepted: 4, serverTime: NOW });
    const first = await call<SummaryBody>('/practice/summary', { token: user.token });
    expect(first.body.days).toEqual([
      { day: '2026-11-25', basicsMs: 4000, metronomeMs: 0, freeMs: 0, totalMs: 4000 },
      { day: '2026-11-26', basicsMs: 1000, metronomeMs: 2000, freeMs: 3000, totalMs: 6000 },
    ]);
    expect(first.body.totals).toEqual({ basicsMs: 5000, metronomeMs: 2000, freeMs: 3000, totalMs: 10000 });
    expect(first.body.updatedAt).toBe(NOW);
    await call('/practice/segments', { token: user.token, body: { deviceId: DEVICE.deviceId, segments } });
    expect((await call<SummaryBody>('/practice/summary', { token: user.token })).body).toEqual(first.body);
    vi.mocked(Date.now).mockReturnValue((NOW + 10) * 1000);
    await call('/practice/segments', { token: user.token, body: { deviceId: 'another-device', segments: [
      { ...segment, elapsedMs: 500, source: 'free', day: '2026-11-25' },
      { ...segment, id: 'metro', elapsedMs: 2500, source: 'free' },
      { ...segment, id: 'other-device-new', elapsedMs: 1500 },
    ] } });
    const merged = await call<SummaryBody>('/practice/summary', { token: user.token });
    expect(merged.body.totals).toEqual({ basicsMs: 6500, metronomeMs: 2500, freeMs: 3000, totalMs: 12000 });
    expect(merged.body.updatedAt).toBe(NOW + 10);
  });

  it('isolates users even with identical segment IDs and ignores spoofed ownership inputs', async () => {
    const first = await register('first_user');
    const second = await register('second_user');
    await call('/practice/segments', { token: first.token, body: { deviceId: DEVICE.deviceId, userId: second.user.id, segments: [segment] } });
    expect((await call<SummaryBody>('/practice/summary', { token: second.token })).body.totals.totalMs).toBe(0);
    await call('/practice/segments', { token: second.token, body: { deviceId: DEVICE.deviceId, segments: [{ ...segment, elapsedMs: 2000 }] } });
    expect((await call<SummaryBody>(`/practice/summary?userId=${second.user.id}`, { token: first.token })).body.totals.totalMs).toBe(1000);
    expect((await call<SummaryBody>('/practice/summary', { token: second.token })).body.totals.totalMs).toBe(2000);
    expect((await call(`/practice/segments/${segment.id}`, { token: second.token })).status).toBe(404);
    expect((await call(`/users/${first.user.id}/practice/summary`, { token: second.token })).status).toBe(404);
  });

  it('uses an inclusive 365-day default range and matching totals/updatedAt for explicit ranges', async () => {
    const user = await register();
    await call('/practice/segments', { token: user.token, body: { deviceId: DEVICE.deviceId, segments: [
      segment,
      { ...segment, id: 'included', day: dayAt(NOW - 364 * 86400), elapsedMs: 2000 },
      { ...segment, id: 'excluded', day: dayAt(NOW - 365 * 86400), elapsedMs: 4000 },
      { ...segment, id: 'tomorrow', day: dayAt(NOW + 86400), elapsedMs: 8000 },
    ] } });
    expect((await call<SummaryBody>('/practice/summary', { token: user.token })).body.totals.totalMs).toBe(3000);
    vi.mocked(Date.now).mockReturnValue((NOW + 10) * 1000);
    await call('/practice/segments', { token: user.token, body: { deviceId: DEVICE.deviceId, segments: [{ ...segment, id: 'tomorrow', day: dayAt(NOW + 86400), elapsedMs: 8000 }] } });
    const today = await call<SummaryBody>('/practice/summary?from=2026-11-26&to=2026-11-26', { token: user.token });
    expect(today.body.days).toHaveLength(1);
    expect(today.body.totals.totalMs).toBe(1000);
    expect(today.body.updatedAt).toBe(NOW);
    const all = await call<SummaryBody>(`/practice/summary?from=${dayAt(NOW - 365 * 86400)}&to=${dayAt(NOW + 86400)}`, { token: user.token });
    expect(all.body.totals.totalMs).toBe(15000);
    expect(all.body.updatedAt).toBe(NOW + 10);
    const empty = await call<SummaryBody>('/practice/summary?from=2020-01-01&to=2020-01-02', { token: user.token });
    expect(empty.body).toEqual({ days: [], totals: { basicsMs: 0, metronomeMs: 0, freeMs: 0, totalMs: 0 }, updatedAt: NOW + 10 });
  });

  it('accepts empty batches, 500 items and legal boundaries, with at most 40 statements per batch', async () => {
    const user = await register();
    const empty = await call<{ accepted: number }>('/practice/segments', { token: user.token, body: { deviceId: DEVICE.deviceId, segments: [] } });
    expect(empty.status).toBe(200);
    expect(empty.body.accepted).toBe(0);
    const batch = vi.spyOn(env.DB, 'batch');
    const segments = Array.from({ length: 500 }, (_, index) => ({ ...segment, id: `segment-${index}`, elapsedMs: index + 1 }));
    const full = await call<{ accepted: number }>('/practice/segments', { token: user.token, body: { deviceId: DEVICE.deviceId, segments } });
    expect(full.status).toBe(200);
    expect(full.body.accepted).toBe(500);
    expect(batch).toHaveBeenCalledTimes(13);
    expect(batch.mock.calls.every(([statements]) => statements.length <= 40)).toBe(true);
    expect((await call<SummaryBody>('/practice/summary', { token: user.token })).body.totals.totalMs).toBe(125250);
    const boundaries = await call('/practice/segments', { token: user.token, body: { deviceId: DEVICE.deviceId, segments: [
      { ...segment, id: ' '.repeat(160), day: dayAt(NOW - 400 * 86400), elapsedMs: 1 },
      { ...segment, id: '~', day: dayAt(NOW + 86400), elapsedMs: 86400000 },
    ] } });
    expect(boundaries.status).toBe(200);
  });

  it('rejects an entire batch and returns indexed field errors before any writes', async () => {
    const user = await register();
    const batch = vi.spyOn(env.DB, 'batch');
    const response = await call('/practice/segments', { token: user.token, body: { deviceId: DEVICE.deviceId, segments: [
      segment, { id: '', day: '2026-02-30', source: 'bad', elapsedMs: 0 }, null,
    ] } });
    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('validation_failed');
    expect(response.body.errors?.map(error => [error.index, error.field])).toEqual([
      [1, 'id'], [1, 'day'], [1, 'source'], [1, 'elapsedMs'], [2, 'segments'],
    ]);
    expect(batch).not.toHaveBeenCalled();
    const count = await env.DB.prepare('SELECT count(*) AS n FROM practice_segments').first<{ n: number }>();
    expect(count?.n).toBe(0);
  });

  // The 0.8.x pool sends test names in a header; %o escapes control characters.
  it.each([
    ['id', ''], ['id', 'x'.repeat(161)], ['id', '中文'], ['id', '\n'], ['id', '\u007f'],
    ['day', '2026-2-01'], ['day', '2026-02-29'], ['day', '2026-11-31'], ['day', '2026-13-01'],
    ['day', dayAt(NOW - 401 * 86400)], ['day', dayAt(NOW + 2 * 86400)],
    ['source', 'unknown'], ['elapsedMs', 0], ['elapsedMs', -1], ['elapsedMs', 86400001],
    ['elapsedMs', 1.5], ['elapsedMs', '1000'], ['elapsedMs', null],
  ])('rejects invalid %s = %o', async (field, value) => {
    const user = await register();
    const response = await call('/practice/segments', { token: user.token, body: { deviceId: DEVICE.deviceId, segments: [{ ...segment, [field]: value }] } });
    expect(response.status).toBe(400);
    expect(response.body.errors).toContainEqual({ index: 0, field, message: expect.any(String) });
  });

  it('rejects missing arrays/device IDs, too many segments, and invalid summary ranges', async () => {
    const user = await register();
    for (const body of [{ segments: [segment] }, { deviceId: DEVICE.deviceId }, { deviceId: DEVICE.deviceId, segments: {} },
      { deviceId: DEVICE.deviceId, segments: Array.from({ length: 501 }, () => segment) }]) {
      expect((await call('/practice/segments', { token: user.token, body })).status).toBe(400);
    }
    for (const query of ['from=', 'from=2026-02-29', 'to=2026-13-01', 'from=2026-11-27&to=2026-11-26']) {
      expect((await call(`/practice/summary?${query}`, { token: user.token })).status).toBe(400);
    }
  });
});
