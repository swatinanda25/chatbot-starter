# Sourced by the other scripts: local toolchain + .env.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jdk-21.0.12.1+1/Contents/Home}"
export PATH="$JAVA_HOME/bin:$HOME/.local/bin:$PATH"
if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi
