#!/usr/bin/env bash
# Starts the ADK dev UI on :8000 (open http://localhost:8000) to debug the agents, with the app's
# plugins attached and every session signed in as the demo customer (see AdkWebLauncher).
# Needs the gateway (scripts/gateway.sh); runs alongside the app on :8080.
set -euo pipefail
. "$(dirname "$0")/env.sh"
cd "$ROOT"
mkdir -p data
# Its own database: the launcher starts a copy of the app context, which reseeds its database on
# start. Sharing data/store.db would reset the running app's orders mid-demo.
export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:sqlite:./data/adk-web.db}"
exec ./mvnw -q spring-boot:test-run -Dspring-boot.test-run.main-class=com.demo.chatbot.dev.AdkWebLauncher
