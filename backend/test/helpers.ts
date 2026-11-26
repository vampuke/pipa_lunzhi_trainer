import { env } from 'cloudflare:test';
import { expect } from 'vitest';
import worker from '../src/index';
import type { FieldError } from '../src/types';

export const NOW = Date.parse('2026-11-26T12:00:00Z') / 1000;
export const PASSWORD = 'Pipa-Blue-River-739!';
export const NEW_PASSWORD = 'Pipa-Green-Moon-852!';
export const DEVICE = { deviceName: 'Test phone', deviceId: 'test-device-1' };

export interface ErrorBody {
  error: { code: string; message: string };
  retry_after?: number;
  errors?: FieldError[];
}

export interface AuthBody {
  token: string;
  expiresAt: number;
  user: { id: string; username: string; displayName: string };
  recoveryCode: string;
}

export interface MeBody {
  user: AuthBody['user'];
  expiresAt: number;
  session: { id: string; createdAt: number; lastUsedAt: number };
}

export interface SummaryBody {
  days: { day: string; basicsMs: number; metronomeMs: number; freeMs: number; totalMs: number }[];
  totals: { basicsMs: number; metronomeMs: number; freeMs: number; totalMs: number };
  updatedAt: number;
}

export async function call<T = ErrorBody>(path: string, options: {
  method?: string;
  body?: unknown;
  raw?: string;
  token?: string;
  ip?: string;
  headers?: Record<string, string>;
} = {}): Promise<{ status: number; body: T; headers: Headers }> {
  const headers = new Headers({ 'CF-Connecting-IP': options.ip ?? '192.0.2.10', ...options.headers });
  if (options.token !== undefined) headers.set('Authorization', `Bearer ${options.token}`);
  const init: RequestInit = { method: options.method ?? (options.body === undefined && options.raw === undefined ? 'GET' : 'POST'), headers };
  if (options.body !== undefined || options.raw !== undefined) {
    if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    init.body = options.raw ?? JSON.stringify(options.body);
  }
  const response = await worker.fetch(new Request(`https://pipa-api.test/api/v1${path}`, init), env);
  const body: unknown = response.status === 204 ? null : await response.json();
  expect(response.headers.get('Cache-Control')).toBe('no-store');
  expect(response.headers.get('X-Request-Id')).toMatch(/^[a-f0-9-]{36}$/);
  return { status: response.status, body: body as T, headers: response.headers };
}

export async function register(username = 'pipa_user', ip = '192.0.2.10'): Promise<AuthBody> {
  const response = await call<AuthBody>('/auth/register', { body: { username, password: PASSWORD, ...DEVICE }, ip });
  expect(response.status).toBe(201);
  return response.body;
}

export async function login(username = 'pipa_user', password = PASSWORD, ip = '192.0.2.10'): Promise<AuthBody> {
  const response = await call<AuthBody>('/auth/login', { body: { username, password, ...DEVICE }, ip });
  expect(response.status).toBe(200);
  return response.body;
}
