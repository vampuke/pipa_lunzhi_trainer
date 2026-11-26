#!/usr/bin/env bash
set -euo pipefail
umask 077

for smoke_command in curl python3; do
  command -v "$smoke_command" >/dev/null || { printf 'FAIL: missing %s\n' "$smoke_command" >&2; exit 1; }
done

smoke_api='https://pipa-api.alanvampuke.top/api/v1'
smoke_dir=$(mktemp -d)
smoke_passed=false
cleanup() {
  rm -rf "$smoke_dir"
  if [ "$smoke_passed" != true ]; then printf 'FAIL: production smoke did not complete\n' >&2; fi
}
trap cleanup EXIT

# Credentials stay in a mode-700 temporary directory, never in output or curl argv.
python3 - "$smoke_dir" <<'PY'
import datetime, json, pathlib, secrets, sys, uuid
directory = pathlib.Path(sys.argv[1])
day = datetime.datetime.now(datetime.timezone.utc).date().isoformat()
registration = {'username': 'smoke_' + secrets.token_hex(10), 'password': secrets.token_urlsafe(24),
                'deviceName': 'Production smoke', 'deviceId': str(uuid.uuid4())}
(directory / 'register.body').write_text(json.dumps(registration))
(directory / 'segments.body').write_text(json.dumps({'deviceId': registration['deviceId'], 'segments': [
    {'id': 'd:' + registration['deviceId'] + ':basics', 'day': day, 'source': 'basics', 'elapsedMs': 1000},
    {'id': 'd:' + registration['deviceId'] + ':metronome', 'day': day, 'source': 'metronome', 'elapsedMs': 2000},
]}))
(directory / 'day').write_text(day)
PY

request() {
  local expected=$1 method=$2 path=$3 response=$4 payload=${5:-} auth=${6:-}
  local status
  local -a curl_args=(--silent --show-error --connect-timeout 10 --max-time 30 --proto '=https' --tlsv1.2
    --request "$method" --output "$smoke_dir/$response" --write-out '%{http_code}')
  if [ -n "$payload" ]; then curl_args+=(--header 'Content-Type: application/json' --data-binary "@$smoke_dir/$payload"); fi
  if [ -n "$auth" ]; then curl_args+=(--header "@$smoke_dir/$auth"); fi
  status=$(curl "${curl_args[@]}" "$smoke_api$path")
  if [ "$status" != "$expected" ]; then
    printf 'FAIL: %s %s returned HTTP %s (expected %s)\n' "$method" "$path" "$status" "$expected" >&2
    return 1
  fi
}

save_auth() {
  python3 - "$smoke_dir/$1" "$smoke_dir/$2" <<'PY'
import json, pathlib, re, sys
token = json.loads(pathlib.Path(sys.argv[1]).read_text())['token']
assert re.fullmatch(r'[A-Za-z0-9_-]{43}', token), 'Invalid session token format'
pathlib.Path(sys.argv[2]).write_text('Authorization: Bearer ' + token + '\n')
PY
}

verify_summary() {
  python3 - "$smoke_dir/$1" "$smoke_dir/day" <<'PY'
import json, pathlib, sys
summary = json.loads(pathlib.Path(sys.argv[1]).read_text())
totals = {'basicsMs': 1000, 'metronomeMs': 2000, 'freeMs': 0, 'totalMs': 3000}
assert summary['totals'] == totals, 'Incorrect summary totals'
assert summary['days'] == [{'day': pathlib.Path(sys.argv[2]).read_text(), **totals}], 'Incorrect daily summary'
assert isinstance(summary['updatedAt'], int), 'updatedAt must be epoch seconds'
PY
}

request 200 GET '/health' health.response
request 201 POST '/auth/register' register.response register.body
save_auth register.response register.headers
request 200 POST '/auth/login' login.response register.body
save_auth login.response login.headers
request 200 POST '/practice/segments' upload.response segments.body register.headers
smoke_day=$(cat "$smoke_dir/day")
smoke_summary="/practice/summary?from=$smoke_day&to=$smoke_day"
request 200 GET "$smoke_summary" summary.response '' login.headers
verify_summary summary.response
request 200 POST '/practice/segments' replay.response segments.body login.headers
request 200 GET "$smoke_summary" replay-summary.response '' login.headers
verify_summary replay-summary.response
request 401 GET '/practice/summary' unauthorized.response

python3 - "$smoke_dir" <<'PY'
import json, pathlib, secrets, sys
directory = pathlib.Path(sys.argv[1])
registration = json.loads((directory / 'register.body').read_text())
response = json.loads((directory / 'register.response').read_text())
body = {key: value for key, value in registration.items() if key != 'password'}
body.update(recoveryCode=response['recoveryCode'], newPassword=secrets.token_urlsafe(24))
(directory / 'recover.body').write_text(json.dumps(body))
PY

request 200 POST '/auth/recover' recover.response recover.body
save_auth recover.response recover.headers
request 200 GET "$smoke_summary" recovered-summary.response '' recover.headers
verify_summary recovered-summary.response
request 401 GET '/auth/me' revoked.response '' login.headers
request 401 POST '/auth/recover' reused-code.response recover.body

python3 - "$smoke_dir" <<'PY'
import json, pathlib, sys
directory = pathlib.Path(sys.argv[1])
for filename in ['unauthorized.response', 'revoked.response']:
    assert json.loads((directory / filename).read_text())['error']['code'] == 'session_invalid'
assert json.loads((directory / 'reused-code.response').read_text())['error']['code'] == 'invalid_recovery_code'
registration = json.loads((directory / 'register.response').read_text())
recovered = json.loads((directory / 'recover.response').read_text())
assert registration['recoveryCode'] != recovered['recoveryCode'], 'Recovery code was not rotated'
for filename in ['upload.response', 'replay.response']:
    assert json.loads((directory / filename).read_text())['accepted'] == 2
PY

request 204 POST '/auth/logout' logout.response '' recover.headers
smoke_passed=true
printf 'PASS: production registration, login, sync, deduplication, isolation from guests and single-use recovery\n'
