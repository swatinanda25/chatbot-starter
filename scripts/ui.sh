#!/usr/bin/env bash
# Serves the chat UI (ui/) on :5173 (open http://localhost:5173). It's plain static files;
# the backend URL is set in ui/config.js. Uses the JDK's built-in jwebserver, so no Node needed.
set -euo pipefail
. "$(dirname "$0")/env.sh"
exec jwebserver -b 127.0.0.1 -p "${UI_PORT:-5173}" -d "$ROOT/ui"
