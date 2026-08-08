#!/usr/bin/env bash
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
mkdir -p out
javac -cp "lib/*" -d out src/darts/common/*.java src/darts/server/*.java src/darts/client/*.java
echo "[SUCCESS] Compilation finished clean."
