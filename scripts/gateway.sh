#!/usr/bin/env bash
# Starts the LiteLLM gateway on :4000, in front of the local Ollama model.
set -euo pipefail
. "$(dirname "$0")/env.sh"

CONFIG="$ROOT/gateway/litellm-config.yaml"
if [ -z "${LANGFUSE_PUBLIC_KEY:-}" ] || [ -z "${LANGFUSE_SECRET_KEY:-}" ]; then
  echo "Langfuse keys not set: starting the gateway without tracing."
  CONFIG="$ROOT/gateway/.litellm-config.no-tracing.yaml"
  grep -v 'callbacks: \["langfuse_otel"\]' "$ROOT/gateway/litellm-config.yaml" > "$CONFIG"
fi

export OLLAMA_API_BASE="${OLLAMA_API_BASE:-http://localhost:11434}"
if ! curl -s -m 2 -o /dev/null "$OLLAMA_API_BASE/api/tags"; then
  echo "WARNING: Ollama is not reachable at $OLLAMA_API_BASE; start it with 'ollama serve'."
fi

exec litellm --config "$CONFIG" --port 4000
