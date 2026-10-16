#!/usr/bin/env bash
# Starts the chatbot API on :8080. The UI is separate: scripts/ui.sh.
set -euo pipefail
. "$(dirname "$0")/env.sh"
cd "$ROOT"
mkdir -p data
exec ./mvnw -q spring-boot:run
