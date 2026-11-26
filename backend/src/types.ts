export interface Env {
  DB: D1Database;
  PEPPER?: string;
}

export interface UserRow {
  id: string;
  username: string;
  display_name: string;
  password_algorithm: string;
  password_salt: string;
  password_hash: string;
  password_iterations: number;
  pepper_version: number;
  auth_version: number;
  recovery_code_hash: string;
  created_at: number;
  updated_at: number;
}

export interface SessionRow {
  id: string;
  user_id: string;
  token_hash: string;
  device_name: string;
  device_id: string;
  created_at: number;
  last_used_at: number;
  expires_at: number;
  revoked_at: number | null;
  revoke_reason: string | null;
}

export interface Authenticated {
  user: UserRow;
  session: SessionRow;
}

export interface Device {
  deviceName: string;
  deviceId: string;
}

export interface FieldError {
  index?: number;
  field: string;
  message: string;
}
