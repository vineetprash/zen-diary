#!/usr/bin/env bash
# One-time: creates the permanent signing key and prints what to add as GitHub Secrets.
# BACK UP the .jks file + passwords somewhere safe. If you lose it, users must uninstall to update.
set -euo pipefail
KS="zen-diary-release.jks"
ALIAS="zendiary"
read -r -s -p "Choose a keystore password (min 6 chars): " PASS; echo
keytool -genkeypair -v -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=Zen Diary, O=Zen Diary, C=IN"
echo
echo "=== Add these GitHub repo secrets (Settings > Secrets and variables > Actions) ==="
echo "KEYSTORE_PASSWORD = (the password you typed)"
echo "KEY_PASSWORD      = (same password)"
echo "KEY_ALIAS         = $ALIAS"
echo "KEYSTORE_BASE64   = (copy the block below)"
base64 -w0 "$KS" 2>/dev/null || base64 "$KS" | tr -d '\n'
echo; echo
echo "=== SHA-1 fingerprint (needed for Google Drive OAuth client) ==="
keytool -list -v -keystore "$KS" -alias "$ALIAS" -storepass "$PASS" | grep -E "SHA1|SHA-1"
