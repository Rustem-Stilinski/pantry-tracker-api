#!/usr/bin/env bash
# End-to-end check against a running API (default http://localhost:8080), the way a browser uses it:
# session cookie + XSRF-TOKEN cookie echoed in the X-XSRF-TOKEN header.
# Used by CI against a real PostgreSQL; also handy locally after `docker compose up`.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

xsrf() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$JAR" | tail -n 1; }

# call METHOD PATH [JSON_BODY] -> prints the response body, fails on HTTP errors
call() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS --fail-with-body -b "$JAR" -c "$JAR" -X "$method" -H "X-XSRF-TOKEN: $(xsrf)")
  if [[ -n "$body" ]]; then args+=(-H "Content-Type: application/json" -d "$body"); fi
  curl "${args[@]}" "$BASE$path"
}

check() { echo "  ✓ $1"; }

echo "Smoke test against $BASE"

# Signed out: 401, and the response sets the XSRF-TOKEN cookie.
status=$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" "$BASE/api/auth/me")
[[ "$status" == "401" ]] || { echo "expected 401 from /me, got $status"; exit 1; }
[[ -n "$(xsrf)" ]] || { echo "no XSRF-TOKEN cookie was set"; exit 1; }
check "signed out -> 401 with XSRF cookie"

# A write without the CSRF header is rejected.
status=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -X POST -H "Content-Type: application/json" \
  -d '{"email":"x@example.com","password":"correct-horse-9","displayName":"X"}' "$BASE/api/auth/register")
[[ "$status" == "403" ]] || { echo "expected 403 without CSRF header, got $status"; exit 1; }
check "write without CSRF header -> 403"

email="smoke-$(date +%s)-$RANDOM@example.com"
call POST /api/auth/register "{\"email\":\"$email\",\"password\":\"correct-horse-9\",\"displayName\":\"Smoke Test\"}" \
  | jq -e --arg e "$email" '.email == $e' > /dev/null
check "registered and signed in"

call GET /api/auth/me | jq -e '.displayName == "Smoke Test"' > /dev/null
check "session cookie works"

household=$(call POST /api/households '{"name":"CI kitchen"}')
hid=$(jq -r '.id' <<< "$household")
jq -e '.inviteCode | test("^[A-Z2-9]{8}$")' <<< "$household" > /dev/null
check "started a log with an invite code"

call POST "/api/households/$hid/entries" \
  '{"itemName":"Paper towels","quantity":2,"unit":"roll","category":"Kitchen","note":"smoke test"}' > /dev/null
entry=$(call POST "/api/households/$hid/entries" '{"itemName":"paper towels","quantity":1,"unit":"roll","category":"Kitchen"}')
entry_id=$(jq -r '.id' <<< "$entry")
check "logged two entries"

month=$(date -u +%Y-%m)
call GET "/api/households/$hid/entries?month=$month&tz=UTC" | jq -e 'length == 2' > /dev/null
check "entries for $month"

call GET "/api/households/$hid/totals?month=$month&tz=UTC" \
  | jq -e '.[0].itemKey == "paper towels" and .[0].quantity == 3 and .[0].times == 2' > /dev/null
check "totals group 'Paper towels' and 'paper towels' (3 rolls, 2 times)"

call GET "/api/households/$hid/items/frequent" | jq -e '.[0].timesUsed == 2 and .[0].quantity == 1' > /dev/null
check "frequent items carry the last amount"

# Live updates: the stream opens and sends its first event.
sse=$(curl -sN --max-time 3 -b "$JAR" -H "Accept: text/event-stream" "$BASE/api/households/$hid/events" || true)
grep -qE "event: ?ready" <<< "$sse" || { echo "SSE stream did not send 'ready': $sse"; exit 1; }
check "live-updates stream opens"

status=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -X DELETE -H "X-XSRF-TOKEN: $(xsrf)" \
  "$BASE/api/households/$hid/entries/$entry_id")
[[ "$status" == "204" ]] || { echo "expected 204 from delete, got $status"; exit 1; }
call GET "/api/households/$hid/entries?month=$month&tz=UTC" | jq -e 'length == 1' > /dev/null
check "removed an entry"

status=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -X POST -H "X-XSRF-TOKEN: $(xsrf)" "$BASE/api/auth/logout")
[[ "$status" == "204" ]] || { echo "expected 204 from logout, got $status"; exit 1; }
status=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/households")
[[ "$status" == "401" ]] || { echo "expected 401 after logout, got $status"; exit 1; }
check "signed out"

echo "All smoke checks passed."
