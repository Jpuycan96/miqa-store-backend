#!/usr/bin/env bash
# Manual Linux command. Environment supplied by systemd-run; never source an env file.
set +x
set -euo pipefail
umask 077
JAVA_BIN=${JAVA_BIN:-/usr/bin/java}
APP_JAR=${APP_JAR:-/opt/miqa-store/app/miqa-store-backend.jar}
if [[ $EUID -eq 0 || ! -t 0 ]]; then
  echo 'Run interactively as the dedicated miqa-store user, not root.' >&2
  exit 1
fi
if [[ ! -r "$APP_JAR" || ! -x "$JAVA_BIN" ]]; then
  echo 'Check APP_JAR and the installed Java 21 executable.' >&2
  exit 1
fi
if ! "$JAVA_BIN" -version 2>&1 | grep -Eq 'version "21[.+"]'; then
  echo 'Java 21 is required.' >&2
  exit 1
fi
cleanup() { unset ADMIN_BOOTSTRAP_USERNAME ADMIN_BOOTSTRAP_PASSWORD confirmation; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
read -r -p 'First admin username: ' ADMIN_BOOTSTRAP_USERNAME
read -r -s -p 'Password (at least 12 characters, at most 72 UTF-8 bytes): ' ADMIN_BOOTSTRAP_PASSWORD
printf '\n'
read -r -s -p 'Confirm password: ' confirmation
printf '\n'
if [[ "$ADMIN_BOOTSTRAP_PASSWORD" != "$confirmation" ]]; then
  echo 'Passwords do not match.' >&2
  exit 1
fi
unset confirmation
export ADMIN_BOOTSTRAP_USERNAME ADMIN_BOOTSTRAP_PASSWORD
# DB/JWT/media variables must come from the protected external environment file.
# No passwords in command arguments, no web listener, no schema migrations.
"$JAVA_BIN" -Xms64m -Xmx192m -jar "$APP_JAR" \
  --spring.profiles.active=prod,admin-bootstrap \
  --spring.main.web-application-type=none \
  --spring.flyway.enabled=false
echo 'First administrator created. Temporary password variables are being cleared.'
