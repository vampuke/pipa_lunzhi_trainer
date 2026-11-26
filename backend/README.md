# Pipa account and activity API

Plain TypeScript Cloudflare Worker `pipa-api`, backed by the existing D1 database
`pipa-activity`. There are no runtime npm dependencies. All DB and API timestamps
(`createdAt`, `lastUsedAt`, `expiresAt`, `serverTime`, `updatedAt`, `retry_after`)
are integer epoch **seconds** or durations in seconds. Practice durations remain
integer milliseconds. Days and date bounds use UTC.

Use Node 24 on a development machine or CI with adequate memory:

```sh
cd backend
npm install --no-audit --no-fund --no-package-lock
npm run typecheck
npm test
npx wrangler d1 migrations apply pipa-activity --local
npx wrangler dev
```

The backend toolchain matches the tested combination used by `dining_log`:

| Package | Exact version |
| --- | --- |
| `@cloudflare/vitest-pool-workers` | `0.8.49` |
| `@cloudflare/workers-types` | `4.20250620.0` |
| `@types/node` | `22.15.0` |
| `typescript` | `5.8.3` |
| `vitest` | `3.2.4` |
| `wrangler` | `4.23.0` |

Wrangler and the Vitest Miniflare runtime both use compatibility date
`2025-06-20`. Tests use the pool's `defineWorkersConfig` API and load the real D1
migrations with `readD1Migrations`; the setup applies them before tests run.
Vitest supplies the compatible runner and snapshot packages without direct pins.

There is no committed `package-lock.json`. Both backend CI jobs use the install
command above, with lockfile caching disabled. Exact direct dependencies are
pinned; transitive versions are resolved at install time. This avoids relying on
the incompatible hand-assembled lockfile or regenerating it on the small VPS.

For local development, set `PEPPER` in an untracked `backend/.dev.vars` file.
Use a long randomly generated value. Tests inject their own explicitly test-only
pepper and use Miniflare D1 with the real SQL migration; they never access remote
bindings. `PEPPER` is deliberately optional in the TypeScript environment so the
missing-secret path can be tested. Auth fails with a safe 500 if it is absent.

Set the production secret interactively, without putting it in wrangler.toml or
source control:

```sh
npx wrangler secret put PEPPER
```

Keep this value stable: changing it invalidates stored passwords, recovery codes
and session tokens. `pepper_version` is currently 1. Passwords use HMAC-SHA256
pepper prehashing followed by PBKDF2-SHA256, 10,000 iterations, a 16-byte random
salt and a 32-byte derived hash. Stored binary values are unpadded base64url;
session token hashes are SHA-256 hex of the pepper concatenated with the token.
The password denylist contains over 200 common passwords; creation requires
8–128 characters. Verification uses each user's stored iteration count.

Deployment order is tests, the production migration, then the Worker. From a
development machine, after arranging the Cloudflare credentials and `PEPPER`:

```sh
npx wrangler d1 migrations apply pipa-activity --remote
npx wrangler deploy
```

The migration command is noninteractive when `CI=true` (Wrangler auto-confirms
without a TTY); it does not support a `--yes` flag. The workflow runs this command
before `cloudflare/wrangler-action@v3`. Configure GitHub secrets
`CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`. Only a main-branch push deploys,
after tests succeed. No resources need to be created: the database ID and the
`pipa-api.alanvampuke.top/*` route under `alanvampuke.top` are already configured.
The Worker secret must be set before serving auth requests.

The JSON API lives at `https://pipa-api.alanvampuke.top/api/v1`:

| Method | Path | Result |
| --- | --- | --- |
| GET | `/health` | `{ok:true,apiVersion:1}`; also available at root `/health` |
| POST | `/auth/register` | 201 user, token, expiry and recovery code |
| POST | `/auth/login` | User, token and expiry; multiple devices may coexist |
| GET | `/auth/me` | User, expiry and current session metadata |
| POST | `/auth/logout` | 204; revokes the current session |
| POST | `/auth/password` | Token and expiry; revokes all old sessions, preserves recovery code |
| POST | `/auth/recover` | User, token, expiry and replacement recovery code; revokes old sessions |
| POST | `/auth/recovery-code` | Replacement recovery code, after password verification |
| POST | `/practice/segments` | `{accepted,serverTime}` |
| GET | `/practice/summary?from=&to=` | Daily three-source durations, totals and updatedAt |

Registration/login require `{username,password,deviceName,deviceId}`. Recovery
requires `{username,recoveryCode,newPassword,deviceName,deviceId}`. Password change
requires `{currentPassword,newPassword}`; recovery-code regeneration requires
`{password}`. Protected endpoints require `Authorization: Bearer <token>`.
Device names are 1–80 characters without controls; device IDs are 1–128 printable
ASCII characters. Username normalization is NFKC, trim, lowercase, then validation.
Passwords are preserved exactly. Recovery codes are case-sensitive, with outer
whitespace trimmed on use.

Uploads require `{deviceId,segments:[{id,day,source,elapsedMs}]}`. At most 500
segments are validated before any write; errors include an `errors` array of
`{index,field,message}`. Writes are chunked into atomic D1 batches of at most 40
statements. A failure between chunks may save a prefix: replay the full batch
safely. Upserts preserve original day/source/device metadata and take the maximum
duration. User ownership comes solely from the bearer token. Unavailable resources
return 404. Summary ranges are inclusive, default to today minus 364 days through
today, and filter both totals and maximum update time. Empty ranges return zero
totals and the current server time.

Errors use `{"error":{"code":"...","message":"..."}}`. Rate limits add a
top-level `retry_after` and a matching `Retry-After` header. Validation adds the
top-level `errors` array. Expired/revoked/missing sessions return 401
`session_invalid`; method mismatches return 405 with `Allow`. Bodies are capped at
256 KiB while streaming, require JSON objects and `application/json`. Every
response includes `Cache-Control: no-store` and a generated `X-Request-Id`.

Five failures per normalized username/IP pair in a sliding 15-minute window lock
that pair for 15 minutes. Login, recovery, password changes and code regeneration
share the failure limit. The fifth failure returns 429. Registration attempts
(including username conflicts) have a separate 30/hour/IP cap. Successful
verification clears unlocked failures. Timestamp arrays are bounded; no
unbounded event log is retained. A conditional UPSERT admits/counts each attempt
atomically. An in-flight verification that passed its lock read can complete
after another request establishes a lock; this D1 limitation is documented in
the code. A lock prevents further verification and cannot be extended by blocked
attempts. Password and recovery mutations use compare-and-swap versions and
`changes()` guards within D1.batch, including session revocation and issuance,
so competing recovery-code uses have only one winner.

After deployment, the exact production smoke sequence below is implemented by
`scripts/smoke.sh`. It needs Bash, curl and Python 3; it creates a random test
account and two retained test segments, stores credentials in a private temporary
directory, verifies math and errors, removes temporary files and prints PASS/FAIL:

```sh
cd backend
bash scripts/smoke.sh
```

These are the script's ordered curl requests (`request` uses `curl --request`,
`--data-binary @<payload>`, `--header @<auth file>` and checks the exact HTTP code):

```bash
request 200 GET '/health' health.response
request 201 POST '/auth/register' register.response register.body
save_auth register.response register.headers
request 200 POST '/auth/login' login.response register.body
save_auth login.response login.headers
request 200 POST '/practice/segments' upload.response segments.body register.headers
request 200 GET "$smoke_summary" summary.response '' login.headers
verify_summary summary.response                 # 1000 + 2000 = 3000 ms
request 200 POST '/practice/segments' replay.response segments.body login.headers
request 200 GET "$smoke_summary" replay-summary.response '' login.headers
verify_summary replay-summary.response          # still 3000 ms
request 401 GET '/practice/summary' unauthorized.response
request 200 POST '/auth/recover' recover.response recover.body
save_auth recover.response recover.headers
request 200 GET "$smoke_summary" recovered-summary.response '' recover.headers
verify_summary recovered-summary.response
request 401 GET '/auth/me' revoked.response '' login.headers
request 401 POST '/auth/recover' reused-code.response recover.body
request 204 POST '/auth/logout' logout.response '' recover.headers
```

The default summary URL in this sequence uses the two generated segments' UTC day
as both `from` and `to`. Do not print the saved responses: registration/recovery
responses contain credentials. The script itself outputs only failure status or
PASS and has no automatic retries of credential mutations.

Local verification of this toolchain passed strict typecheck and all 106 Worker
tests (four files, including the real D1 migration) on Node 26.12.1. To fit the
small VPS, checks reused the sibling project's installed toolchain plus the
pinned Node types; a fresh dependency install was stopped at 30 seconds. A fresh
Node 24 install and test run in GitHub Actions remain unverified.

The bundled `workerd` 1.20250617.0 warns that it falls back from the configured
`2025-06-20` compatibility date to `2025-06-17`. The successful test command also
logged a Miniflare socket error and timeout during shutdown; clean shutdown on
CI remains unverified. No deployment or production smoke was performed.
