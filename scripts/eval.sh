#!/usr/bin/env bash
# Runs the golden-set evals through the real agents and the gateway (the gateway must be running).
set -euo pipefail
. "$(dirname "$0")/env.sh"
cd "$ROOT"
mkdir -p data
exec ./mvnw -q -Peval test "$@"
