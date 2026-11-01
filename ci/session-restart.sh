#!/usr/bin/env bash
# Sign-ins are kept in PostgreSQL, so restarting the API must not sign anyone out.
#   ./ci/session-restart.sh before   # sign up and keep the cookies
#   (restart the API)
#   ./ci/session-restart.sh after    # the same cookies are still signed in, and writes still pass CSRF
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
JAR="${JAR:-/tmp/session-restart.cookies}"
xsrf() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$JAR" | tail -n 1; }

case "${1:-}" in
  before)
    rm -f "$JAR"
    curl -sS -o /dev/null -c "$JAR" "$BASE/api/auth/me"
    email="restart-$(date +%s)-$RANDOM@example.com"
    curl -sS --fail-with-body -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(xsrf)" -H "Content-Type: application/json" \
      -d "{\"email\":\"$email\",\"password\":\"correct-horse-9\",\"displayName\":\"Restart Test\"}" \
      "$BASE/api/auth/register" > /dev/null
    curl -sS --fail-with-body -b "$JAR" "$BASE/api/auth/me" | jq -e '.displayName == "Restart Test"' > /dev/null
    echo "  ✓ signed in before the restart"
    ;;
  after)
    status=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" "$BASE/api/auth/me")
    [[ "$status" == "200" ]] || { echo "signed out by the restart: /me answered $status"; exit 1; }
    curl -sS --fail-with-body -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(xsrf)" -H "Content-Type: application/json" \
      -d '{"name":"After restart"}' "$BASE/api/households" | jq -e '.name == "After restart"' > /dev/null
    echo "  ✓ still signed in after the restart, and writes work"
    ;;
  *)
    echo "usage: $0 before|after"; exit 2
    ;;
esac
