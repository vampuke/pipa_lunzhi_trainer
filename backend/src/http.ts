import type { Device, FieldError } from './types';

export const MAX_BODY_BYTES = 256 * 1024;

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly details: { retry_after?: number; errors?: FieldError[] } = {},
    public readonly headers: Record<string, string> = {},
  ) {
    super(message);
  }
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

export function errorResponse(error: ApiError): Response {
  const response = json({ error: { code: error.code, message: error.message }, ...error.details }, error.status);
  for (const [key, value] of Object.entries(error.headers)) response.headers.set(key, value);
  return response;
}

export function invalid(field: string, message: string): never {
  throw new ApiError(400, 'validation_failed', 'Invalid request.', { errors: [{ field, message }] });
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export async function readJson(request: Request): Promise<Record<string, unknown>> {
  const length = request.headers.get('Content-Length');
  if (length !== null && Number(length) > MAX_BODY_BYTES) {
    throw new ApiError(413, 'request_too_large', 'Request body is too large.');
  }
  const type = request.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase();
  if (type !== 'application/json') throw new ApiError(415, 'unsupported_media_type', 'Expected application/json.');
  if (request.body === null) throw new ApiError(400, 'invalid_json', 'Expected a JSON object.');
  const reader = request.body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let size = 0;
  let body = '';
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      size += chunk.value.byteLength;
      if (size > MAX_BODY_BYTES) {
        await reader.cancel();
        throw new ApiError(413, 'request_too_large', 'Request body is too large.');
      }
      body += decoder.decode(chunk.value, { stream: true });
    }
    body += decoder.decode();
    const parsed: unknown = JSON.parse(body);
    if (!isRecord(parsed)) throw new ApiError(400, 'invalid_json', 'Expected a JSON object.');
    return parsed;
  } catch (error: unknown) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(400, 'invalid_json', 'Expected a JSON object.');
  } finally {
    reader.releaseLock();
  }
}

export function stringField(body: Record<string, unknown>, field: string, min: number, max: number): string {
  const value = body[field];
  if (typeof value !== 'string' || value.length < min || value.length > max) {
    return invalid(field, `Must be a string of ${min} to ${max} characters.`);
  }
  return value;
}

export function deviceIdField(body: Record<string, unknown>): string {
  const deviceId = stringField(body, 'deviceId', 1, 128);
  if (!/^[\x20-\x7e]+$/.test(deviceId)) invalid('deviceId', 'Must contain printable ASCII only.');
  return deviceId;
}

export function deviceFields(body: Record<string, unknown>): Device {
  const deviceName = stringField(body, 'deviceName', 1, 80).trim();
  if (deviceName.length === 0 || /[\x00-\x1f\x7f]/.test(deviceName)) invalid('deviceName', 'Must be a nonempty name without control characters.');
  return { deviceName, deviceId: deviceIdField(body) };
}

export function nowSeconds(): number {
  return Math.floor(Date.now() / 1000);
}
