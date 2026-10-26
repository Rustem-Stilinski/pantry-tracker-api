#!/usr/bin/env bash
# Turns the useful lines of a failed build or app log into one GitHub annotation, so the cause
# shows up on the run's summary page (and in pull requests) without opening the full log.
#   ci/annotate-errors.sh build.log "Build failed"
set -uo pipefail

log="${1:?log file}"
title="${2:-Errors}"
[[ -f "$log" ]] || exit 0

# Maven errors, test failures, Spring log errors and exception causes, without Maven's help boilerplate.
lines=$(grep -E '^\[ERROR\]|Caused by:|Exception:| ERROR [0-9]+ ---| WARN [0-9]+ --- .*Resolved \[' "$log" \
  | grep -vE 'To see the full stack trace|Re-run Maven|For more information about the errors|Help 1\]|^\[ERROR\] *$|-> \[Help' \
  | awk '!seen[$0]++' \
  | head -n 80)

[[ -n "$lines" ]] || lines="(no error lines found; see the full log)"

# Workflow commands need %, CR and LF escaped in the message.
message=$(printf '%s' "$lines" | cut -c1-400 | sed -e 's/%/%25/g' -e 's/\r/%0D/g' | awk 'BEGIN{ORS="%0A"} {print}')
echo "::error title=${title}::${message}"
