#!/usr/bin/env bash
set -euo pipefail

image="${1:-core-banking/funds-core:accounting-kernel}"
suffix="$$"
network="funds-smoke-${suffix}"
missing_container="funds-smoke-missing-${suffix}"
unreachable_container="funds-smoke-unreachable-${suffix}"
postgres_container="funds-smoke-postgres-${suffix}"
ready_container="funds-smoke-ready-${suffix}"
evidence_dir="$(mktemp -d)"

cleanup() {
  docker rm -f "$missing_container" "$unreachable_container" "$ready_container" "$postgres_container" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf "$evidence_dir"
}
trap cleanup EXIT INT TERM

for command_name in docker curl timeout; do
  command -v "$command_name" >/dev/null || { echo "missing command: $command_name" >&2; exit 2; }
done

set +e
timeout 25s docker run --name "$missing_container" --memory=640m --cpus=0.60 --pids-limit=256 \
  "$image" >"$evidence_dir/missing.log" 2>&1
missing_status=$?
set -e
if [[ $missing_status -eq 0 || $missing_status -eq 124 ]]; then
  echo "missing-input probe did not fail closed" >&2
  exit 1
fi
if ! grep -Eq 'SRCFG00011|Production datasource configuration is missing or blank' "$evidence_dir/missing.log"; then
  echo "missing-input probe lacked the stable fail-closed diagnostic" >&2
  exit 1
fi
if grep -Eq 'sensitive-password|jdbc:postgresql://[^ ]+:[^ ]+@' "$evidence_dir/missing.log"; then
  echo "missing-input diagnostic exposed a secret" >&2
  exit 1
fi
echo "missing-input probe: nonzero exit with non-secret fail-closed diagnostic"

docker run -d --name "$unreachable_container" --memory=640m --cpus=0.60 --pids-limit=256 \
  -p 127.0.0.1::8080 \
  -e FUNDS_DB_JDBC_URL=jdbc:postgresql://127.0.0.1:1/funds \
  -e FUNDS_APP_DB_USER=funds_app -e FUNDS_APP_DB_PASSWORD=probe-password \
  "$image" >/dev/null
unreachable_port="$(docker port "$unreachable_container" 8080/tcp | sed -E 's/.*:([0-9]+)$/\1/')"
deadline=$((SECONDS + 25))
unreachable_result=""
while (( SECONDS < deadline )); do
  if [[ "$(docker inspect -f '{{.State.Running}}' "$unreachable_container")" != "true" ]]; then
    unreachable_result="process-exited"
    break
  fi
  status="$(curl --silent --show-error --max-time 2 -o "$evidence_dir/unreachable.json" -w '%{http_code}' \
    "http://127.0.0.1:${unreachable_port}/q/health/ready" 2>/dev/null || true)"
  if [[ "$status" =~ ^[0-9]{3}$ && "$status" != "000" && "$status" != "200" ]]; then
    unreachable_result="readiness-${status}"
    break
  fi
  sleep 1
done
if [[ -z "$unreachable_result" ]]; then
  echo "unreachable-database probe neither exited nor reported unhealthy readiness" >&2
  exit 1
fi
echo "unreachable-database probe: ${unreachable_result}"

docker network create "$network" >/dev/null
docker run -d --name "$postgres_container" --network "$network" --memory=512m --cpus=0.60 --pids-limit=256 \
  -e POSTGRES_DB=funds -e POSTGRES_USER=funds_app -e POSTGRES_PASSWORD=probe-password \
  postgres:18.6-bookworm >/dev/null
deadline=$((SECONDS + 30))
until docker exec "$postgres_container" pg_isready -U funds_app -d funds >/dev/null 2>&1; do
  if (( SECONDS >= deadline )); then
    echo "PostgreSQL 18.6 did not become ready" >&2
    exit 1
  fi
  sleep 1
done

docker run -d --name "$ready_container" --network "$network" --memory=640m --cpus=0.60 --pids-limit=256 \
  -p 127.0.0.1::8080 \
  -e FUNDS_DB_JDBC_URL="jdbc:postgresql://${postgres_container}:5432/funds" \
  -e FUNDS_APP_DB_USER=funds_app -e FUNDS_APP_DB_PASSWORD=probe-password \
  "$image" >/dev/null
ready_port="$(docker port "$ready_container" 8080/tcp | sed -E 's/.*:([0-9]+)$/\1/')"
deadline=$((SECONDS + 30))
ready_status=""
while (( SECONDS < deadline )); do
  ready_status="$(curl --silent --show-error --max-time 2 -o "$evidence_dir/ready.json" -w '%{http_code}' \
    "http://127.0.0.1:${ready_port}/q/health/ready" 2>/dev/null || true)"
  [[ "$ready_status" == "200" ]] && break
  sleep 1
done
if [[ "$ready_status" != "200" ]] \
  || ! grep -q 'Database connections health check' "$evidence_dir/ready.json" \
  || ! grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$evidence_dir/ready.json"; then
  echo "reachable PostgreSQL 18.6 did not produce datasource readiness UP" >&2
  exit 1
fi
echo "reachable-database probe: HTTP 200/UP with datasource check (migration validation remains separate)"
