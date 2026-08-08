#!/usr/bin/env bash
# Generates a self-signed TLS keystore for local DARTS development.
# See docs/05_SECURITY.md — self-signed certs are acceptable for this project.
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERT_DIR="$SCRIPT_DIR/../certs"
KEYSTORE="$CERT_DIR/server.jks"
ALIAS="darts-server"
STOREPASS="changeit"

mkdir -p "$CERT_DIR"

if [[ -f "$KEYSTORE" ]]; then
    echo "Keystore already exists at $KEYSTORE — delete it first to regenerate."
    exit 1
fi

keytool -genkeypair \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 365 \
    -keystore "$KEYSTORE" \
    -storepass "$STOREPASS" \
    -keypass "$STOREPASS" \
    -dname "CN=localhost, OU=DARTS, O=DARTS, L=Local, ST=Local, C=US"

echo "[SUCCESS] Keystore created at $KEYSTORE (alias=$ALIAS, storepass=$STOREPASS)"
