PRAGMA foreign_keys = ON;

-- All timestamps are integer epoch seconds, supplied by the Worker.
CREATE TABLE users (
    id TEXT PRIMARY KEY NOT NULL,
    username TEXT NOT NULL UNIQUE CHECK (
        length(username) BETWEEN 3 AND 32
        AND username = lower(username)
        AND username GLOB '[a-z]*'
        AND username NOT GLOB '*[^a-z0-9_]*'
        AND instr(username, char(0)) = 0
    ),
    display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 80),
    password_algorithm TEXT NOT NULL CHECK (password_algorithm = 'pbkdf2-sha256'),
    password_salt TEXT NOT NULL CHECK (length(password_salt) = 22 AND password_salt NOT GLOB '*[^A-Za-z0-9_-]*' AND instr(password_salt, char(0)) = 0),
    password_hash TEXT NOT NULL CHECK (length(password_hash) = 43 AND password_hash NOT GLOB '*[^A-Za-z0-9_-]*' AND instr(password_hash, char(0)) = 0),
    password_iterations INTEGER NOT NULL CHECK (typeof(password_iterations) = 'integer' AND password_iterations >= 10000),
    pepper_version INTEGER NOT NULL DEFAULT 1 CHECK (pepper_version = 1),
    auth_version INTEGER NOT NULL DEFAULT 1 CHECK (typeof(auth_version) = 'integer' AND auth_version >= 1),
    recovery_code_hash TEXT NOT NULL CHECK (length(recovery_code_hash) = 43 AND recovery_code_hash NOT GLOB '*[^A-Za-z0-9_-]*' AND instr(recovery_code_hash, char(0)) = 0),
    created_at INTEGER NOT NULL CHECK (typeof(created_at) = 'integer' AND created_at >= 0),
    updated_at INTEGER NOT NULL CHECK (typeof(updated_at) = 'integer' AND updated_at >= created_at)
);

CREATE TABLE sessions (
    id TEXT PRIMARY KEY NOT NULL,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE CHECK (length(token_hash) = 64 AND token_hash NOT GLOB '*[^0-9a-f]*' AND instr(token_hash, char(0)) = 0),
    device_name TEXT NOT NULL CHECK (length(device_name) BETWEEN 1 AND 80),
    device_id TEXT NOT NULL CHECK (length(device_id) BETWEEN 1 AND 128),
    created_at INTEGER NOT NULL CHECK (typeof(created_at) = 'integer' AND created_at >= 0),
    last_used_at INTEGER NOT NULL CHECK (typeof(last_used_at) = 'integer' AND last_used_at >= created_at),
    expires_at INTEGER NOT NULL CHECK (typeof(expires_at) = 'integer' AND expires_at > created_at),
    revoked_at INTEGER CHECK (revoked_at IS NULL OR (typeof(revoked_at) = 'integer' AND revoked_at >= created_at)),
    revoke_reason TEXT CHECK (revoke_reason IS NULL OR revoke_reason IN ('logout', 'password_changed', 'recovered'))
);
CREATE INDEX sessions_user_id ON sessions(user_id);
CREATE INDEX sessions_expires_at ON sessions(expires_at);

CREATE TABLE practice_segments (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    segment_id TEXT NOT NULL CHECK (length(segment_id) BETWEEN 1 AND 160 AND segment_id NOT GLOB '*[^ -~]*' AND instr(segment_id, char(0)) = 0),
    day TEXT NOT NULL CHECK (length(day) = 10 AND day GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]' AND date(day, '+0 days') IS NOT NULL AND date(day, '+0 days') = day),
    source TEXT NOT NULL CHECK (source IN ('basics', 'metronome', 'free')),
    elapsed_ms INTEGER NOT NULL CHECK (typeof(elapsed_ms) = 'integer' AND elapsed_ms BETWEEN 1 AND 86400000),
    device_id TEXT NOT NULL CHECK (length(device_id) BETWEEN 1 AND 128),
    created_at INTEGER NOT NULL CHECK (typeof(created_at) = 'integer' AND created_at >= 0),
    updated_at INTEGER NOT NULL CHECK (typeof(updated_at) = 'integer' AND updated_at >= created_at),
    PRIMARY KEY (user_id, segment_id)
);
CREATE INDEX practice_segments_user_day ON practice_segments(user_id, day);

-- Bounded JSON arrays retain the exact recent attempt timestamps for each scope/IP.
CREATE TABLE auth_attempts (
    scope TEXT NOT NULL CHECK (scope GLOB 'u:*' OR scope GLOB 'i:*'),
    ip TEXT NOT NULL CHECK (length(ip) BETWEEN 1 AND 64),
    attempts TEXT NOT NULL CHECK (json_valid(attempts) AND json_type(attempts) = 'array' AND json_array_length(attempts) BETWEEN 1 AND 30),
    locked_until INTEGER NOT NULL DEFAULT 0 CHECK (typeof(locked_until) = 'integer' AND locked_until >= 0),
    updated_at INTEGER NOT NULL CHECK (typeof(updated_at) = 'integer' AND updated_at >= 0),
    PRIMARY KEY (scope, ip)
);
CREATE INDEX auth_attempts_updated_at ON auth_attempts(updated_at);

-- A short-lived row carries changes() across statements in one atomic D1 batch.
CREATE TABLE mutation_guards (
    id TEXT PRIMARY KEY NOT NULL,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL CHECK (typeof(created_at) = 'integer' AND created_at >= 0)
);
