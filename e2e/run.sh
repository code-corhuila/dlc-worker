#!/usr/bin/env bash
# HU-APT-002 end to end on a disposable Docker stage (e2e/compose.yml, network dlc-worker-e2e):
# Appointments built from APPOINTMENTS_REF of its checkout, its schema migrated from dlc-appointments-db, RabbitMQ,
# Mailpit, a Patients double of billing-contact and the worker image with its migrated notifications store.
# Then it runs ReminderEndToEndTest and checks that the worker logs hold no personal data.
#
# Usage:   APPOINTMENTS_API_DIR=../dlc-appointments-api APPOINTMENTS_DB_DIR=../dlc-appointments-db e2e/run.sh
# Options: APPOINTMENTS_REF (default origin/qa), APPOINTMENTS_IMAGE (an existing image instead of building that
#          ref), WORKER_IMAGE (default: built from this checkout),
#          E2E_M2_VOLUME (Maven cache volume, default dlc-worker-m2), KEEP_STAGE=1 (leave the stage running).
#
# Every password, RSA key and service token is generated per run in a temporary folder and removed at the end.
# No IAM issues service tokens yet, so this script signs short-lived RS256 tokens with a throwaway key.
set -euo pipefail
# Git Bash for Windows: keep container paths such as /workspace as they are.
export MSYS_NO_PATHCONV=1

here=$(cd "$(dirname "$0")" && pwd)
repo=$(dirname "$here")
: "${APPOINTMENTS_API_DIR:?set APPOINTMENTS_API_DIR to a dlc-appointments-api checkout}"
: "${APPOINTMENTS_DB_DIR:?set APPOINTMENTS_DB_DIR to a dlc-appointments-db checkout}"
ref=${APPOINTMENTS_REF:-origin/qa}
liquibase=liquibase/liquibase:4.33.0
maven=maven:3.9.11-eclipse-temurin-21

# Docker receives host paths; on Git Bash for Windows those are C:/... paths.
host() { (cd "$1" && { pwd -W 2>/dev/null || pwd; }); }
work=$(mktemp -d)
mkdir -p "$work/secrets" "$work/appointments-api" "$work/appointments-db"
keys=$(host "$work")
compose() { docker compose -f "$(host "$here")/compose.yml" "$@"; }
cleanup() {
  if [ "${KEEP_STAGE:-0}" != 1 ]; then compose down -v --remove-orphans >/dev/null 2>&1 || true; fi
  rm -rf "$work"
}
trap cleanup EXIT
# OpenSSL on Windows may end its output with a carriage return; it never belongs in a secret.
secret() { openssl rand -hex 16 | tr -d '\r\n'; }
step() { printf '\n== %s\n' "$1"; }

step "Throwaway credentials"
export E2E_ADMIN_PASSWORD=$(secret) E2E_BROKER_PASSWORD=$(secret)
export E2E_APPOINTMENTS_PASSWORD=$(secret) E2E_NOTIFICATIONS_PASSWORD=$(secret)
export E2E_SECRETS_DIR=$(host "$work/secrets")
for key in service staff; do
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$keys/$key.key" 2>/dev/null
  openssl pkey -in "$keys/$key.key" -pubout -out "$keys/secrets/$key-public.pem"
done
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '=\r\n'; }
# Service JWT: issuer dlc-e2e-services, subject dlc-worker, one audience, space-separated scopes, 50 minutes.
jwt() {
  local now header claims signature
  now=$(date +%s)
  header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
  claims=$(printf '{"iss":"dlc-e2e-services","sub":"dlc-worker","aud":"%s","scope":"%s","iat":%d,"exp":%d,"jti":"%s"}' \
    "$1" "$2" $((now - 60)) $((now + 2940)) "$(secret)" | b64url)
  signature=$(printf '%s.%s' "$header" "$claims" | openssl dgst -sha256 -sign "$keys/service.key" -binary | b64url)
  printf '%s.%s.%s\n' "$header" "$claims" "$signature"
}
jwt appointments-service "appointments:notification-details:read appointments:notification-outcome:write" \
  > "$work/secrets/worker-to-appointments"
jwt patients-service "patients:billing-contact:read" > "$work/secrets/worker-to-patients"
echo "not-used-by-this-stage" > "$work/secrets/unused-token"
chmod 755 "$work/secrets" && chmod 644 "$work/secrets"/*

step "Images: dlc-appointments-api $ref and dlc-worker"
if [ -z "${APPOINTMENTS_IMAGE:-}" ]; then
  git -C "$APPOINTMENTS_API_DIR" fetch -q origin
  git -C "$APPOINTMENTS_API_DIR" archive "$ref" | tar -x -C "$work/appointments-api"
  docker build -q -f "$(host "$work/appointments-api")/deploy/Dockerfile" -t dlc-appointments-api:e2e \
    "$(host "$work/appointments-api")"
fi
if [ -z "${WORKER_IMAGE:-}" ]; then
  docker build -q -f "$(host "$repo")/deploy/Dockerfile" -t dlc-worker:e2e "$(host "$repo")"
fi

step "PostgreSQL, RabbitMQ, Mailpit and the Patients double"
compose up -d --wait postgres rabbitmq mailpit patients
psql() { compose exec -T postgres psql -v ON_ERROR_STOP=1 -q -U postgres -d di_lucca "$@"; }

# Owner bootstrap, role passwords, Liquibase as the migrator, then the bootstrap again to seal CREATE.
migrate() {
  local dir=$1 bootstrap=$2 script=$3 migrator=$4 runtime=$5 runtime_password=$6
  local LIQUIBASE_COMMAND_PASSWORD
  LIQUIBASE_COMMAND_PASSWORD=$(secret)
  psql < "$dir/$bootstrap"
  psql -c "ALTER ROLE $migrator PASSWORD '$LIQUIBASE_COMMAND_PASSWORD'" \
       -c "ALTER ROLE $runtime PASSWORD '$runtime_password'"
  LIQUIBASE_COMMAND_PASSWORD=$LIQUIBASE_COMMAND_PASSWORD docker run --rm --network dlc-worker-e2e \
    -v "$(host "$dir"):/workspace:ro" -e LIQUIBASE_COMMAND_PASSWORD -e DLC_ENVIRONMENT=dev \
    -e DLC_POSTGRES_HOST=postgres --entrypoint /bin/sh "$liquibase" "/workspace/$script" update >/dev/null
  psql < "$dir/$bootstrap"
}
step "Migrations: appointments ($ref of dlc-appointments-db) and notifications"
git -C "$APPOINTMENTS_DB_DIR" fetch -q origin
git -C "$APPOINTMENTS_DB_DIR" archive "$ref" | tar -x -C "$work/appointments-db"
migrate "$work/appointments-db" deploy/bootstrap.sql deploy/run-migration.sh \
  appointments_migrator appointments_runtime "$E2E_APPOINTMENTS_PASSWORD"
migrate "$repo" db/deploy/bootstrap.sql db/deploy/run-migration.sh \
  notifications_migrator notifications_runtime "$E2E_NOTIFICATIONS_PASSWORD"

step "Appointments and the worker"
compose up -d --wait appointments-api worker
for _ in $(seq 1 60); do
  compose exec -T rabbitmq rabbitmqctl -q list_queues name | grep -qx 'worker.appointment-reminder-requested' && break
  sleep 2
done

step "ReminderEndToEndTest"
docker run --rm --network dlc-worker-e2e -v "$(host "$repo"):/workspace" -v "${E2E_M2_VOLUME:-dlc-worker-m2}:/root/.m2" \
  -w /workspace -e E2E_MAILPIT_URL=http://mailpit:8025 \
  -e "E2E_DATABASE_URL=jdbc:postgresql://postgres:5432/di_lucca?user=postgres&password=$E2E_ADMIN_PASSWORD" \
  -e "E2E_RABBITMQ_URL=amqp://e2e:$E2E_BROKER_PASSWORD@rabbitmq:5672" \
  "$maven" mvn -B -pl worker-app -am test -Dtest=ReminderEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false

step "Worker logs hold no recipient address or name"
if compose logs --no-color worker | grep -E -q 'example\.test|Paciente E2E'; then
  echo "FAIL: personal data in the worker logs" >&2
  exit 1
fi
compose logs --no-color worker | grep -c '"correlationId"' | sed 's/^/worker log lines with correlationId: /'
echo "E2E PASSED"
