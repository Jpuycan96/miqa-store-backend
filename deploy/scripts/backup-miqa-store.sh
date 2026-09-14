#!/usr/bin/env bash
# Production template only. Credentials supplied by systemd LoadCredential.
set +x
set -euo pipefail
umask 077
readonly backup_dir=/opt/miqa-store/backups
readonly pg_bin=/usr/lib/postgresql/18/bin
: "${CREDENTIALS_DIRECTORY:?Run using the prepared backup systemd service}"
export PGPASSFILE="$CREDENTIALS_DIRECTORY/pgpass"
unset PGPASSWORD PGSERVICE PGSERVICEFILE PGOPTIONS
export PGCONNECT_TIMEOUT=10
if [[ ! -r "$PGPASSFILE" || ! -d "$backup_dir" || -L "$backup_dir" ]]; then
  echo 'Backup directory or credential unavailable.' >&2
  exit 1
fi
exec 9>"$backup_dir/.backup.lock"
flock -n 9 || { echo 'Another MIQA backup is running.' >&2; exit 1; }
partial=$(mktemp "$backup_dir/.miqa_store_db_XXXXXXXX.partial")
cleanup() { rm -f -- "$partial"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Explicit connection: never inherit an ERP database or a remote endpoint.
if ! "$pg_bin/pg_dump" --no-password --host=127.0.0.1 --port=5432   --username=miqa_store_app --dbname=miqa_store_db -Fc --file="$partial" 2>/dev/null; then
  echo 'MIQA pg_dump failed; previous backups retained. Check connection, permissions and disk space.' >&2
  exit 1
fi
if [[ ! -s "$partial" ]] || ! "$pg_bin/pg_restore" --list "$partial" >/dev/null 2>&1; then
  echo 'MIQA archive validation failed; previous backups retained.' >&2
  exit 1
fi
stamp=$(date -u +%Y%m%dT%H%M%S%NZ)
archive="$backup_dir/miqa_store_db_${stamp}.dump"
mv -- "$partial" "$archive"
# Retention runs ONLY after a completed and readable dump; exact dedicated prefix.
find "$backup_dir" -maxdepth 1 -type f -name 'miqa_store_db_[0-9]*.dump' -mmin +20160 -delete
echo 'MIQA backup completed; retention of 14 days applied.'
