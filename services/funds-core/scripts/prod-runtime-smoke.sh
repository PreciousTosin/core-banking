#!/usr/bin/env bash
set -euo pipefail

image="${1:-core-banking/funds-core:accounting-kernel}"
suffix="$$"
network="funds-smoke-${suffix}"
missing_container="funds-smoke-missing-${suffix}"
inactive_container="funds-smoke-inactive-${suffix}"
unreachable_container="funds-smoke-unreachable-${suffix}"
postgres_container="funds-smoke-postgres-${suffix}"
ready_container="funds-smoke-ready-${suffix}"
evidence_dir="$(mktemp -d)"

docker_api() {
  local bound="$1"
  shift
  timeout "$bound" docker "$@"
}

cleanup() {
  local primary_status=$?
  local cleanup_status=0
  trap - EXIT INT TERM
  set +e
  docker_api 10s rm -f "$missing_container" "$inactive_container" "$unreachable_container" \
    "$ready_container" "$postgres_container" >/dev/null 2>&1
  [[ $? -eq 0 ]] || cleanup_status=1
  docker_api 10s network rm "$network" >/dev/null 2>&1
  [[ $? -eq 0 ]] || cleanup_status=1
  rm -rf "$evidence_dir"
  if [[ $primary_status -eq 0 && $cleanup_status -ne 0 ]]; then
    echo "runtime-smoke cleanup failed for exact PID-scoped resources" >&2
    primary_status=1
  fi
  exit "$primary_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for command_name in docker curl jq timeout; do
  command -v "$command_name" >/dev/null || { echo "missing command: $command_name" >&2; exit 2; }
done

expect_fail_closed() {
  local container_name="$1"
  local expected_property="$2"
  local output_file="$3"
  shift 3
  set +e
  docker_api 25s run --name "$container_name" --memory=640m --cpus=0.60 --pids-limit=256 \
    "$@" "$image" >"$output_file" 2>&1
  local status=$?
  set -e
  if [[ $status -eq 0 || $status -eq 124 ]]; then
    echo "${expected_property} probe did not fail closed" >&2
    return 1
  fi
  if ! grep -Fq "Production datasource configuration is missing or blank: ${expected_property}" "$output_file"; then
    echo "${expected_property} probe lacked the exact fail-closed diagnostic" >&2
    return 1
  fi
  if grep -Eq 'probe-password|sensitive-password|jdbc:postgresql://[^ ]+:[^ ]+@' "$output_file"; then
    echo "${expected_property} diagnostic exposed a secret" >&2
    return 1
  fi
}

expect_fail_closed "$missing_container" "quarkus.datasource.jdbc.url" "$evidence_dir/missing.log"
echo "missing-input probe: nonzero exit with exact non-secret URL diagnostic"

expect_fail_closed "$inactive_container" "quarkus.datasource.active" "$evidence_dir/inactive.log" \
  -e QUARKUS_DATASOURCE_ACTIVE=false \
  -e FUNDS_DB_JDBC_URL=jdbc:postgresql://127.0.0.1:1/funds \
  -e FUNDS_APP_DB_USER=funds_app -e FUNDS_APP_DB_PASSWORD=probe-password
echo "inactive-override probe: nonzero exit with exact non-secret active diagnostic"

docker_api 20s run -d --name "$unreachable_container" --memory=640m --cpus=0.60 --pids-limit=256 \
  -p 127.0.0.1::8080 \
  -e FUNDS_DB_JDBC_URL=jdbc:postgresql://127.0.0.1:1/funds \
  -e FUNDS_APP_DB_USER=funds_app -e FUNDS_APP_DB_PASSWORD=probe-password \
  "$image" >/dev/null
unreachable_port="$(docker_api 10s port "$unreachable_container" 8080/tcp | sed -E 's/.*:([0-9]+)$/\1/')"
deadline=$((SECONDS + 25))
unreachable_status=""
while (( SECONDS < deadline )); do
  if [[ "$(docker_api 10s inspect -f '{{.State.Running}}' "$unreachable_container")" != "true" ]]; then
    echo "unreachable-database process exited before returning readiness DOWN" >&2
    exit 1
  fi
  unreachable_status="$(curl --silent --show-error --max-time 2 -o "$evidence_dir/unreachable.json" -w '%{http_code}' \
    "http://127.0.0.1:${unreachable_port}/q/health/ready" 2>/dev/null || true)"
  if [[ "$unreachable_status" == "503" ]]; then
    jq -e '.status == "DOWN" and any(.checks[]; .name == "Database connections health check" and .status == "DOWN")' \
      "$evidence_dir/unreachable.json" >/dev/null \
      || { echo "unreachable readiness did not pair datasource check with DOWN" >&2; exit 1; }
    break
  fi
  sleep 1
done
if [[ "$unreachable_status" != "503" ]]; then
  echo "unreachable-database probe did not return exact HTTP 503" >&2
  exit 1
fi
echo "unreachable-database probe: running process returned HTTP 503, aggregate DOWN, datasource DOWN"

docker_api 15s network create "$network" >/dev/null
docker_api 20s run -d --name "$postgres_container" --network "$network" \
  --memory=512m --cpus=0.60 --pids-limit=256 \
  -e POSTGRES_DB=funds -e POSTGRES_USER=funds_app -e POSTGRES_PASSWORD=probe-password \
  postgres:18.6-bookworm >/dev/null
deadline=$((SECONDS + 30))
until docker_api 10s exec "$postgres_container" pg_isready -U funds_app -d funds >/dev/null 2>&1; do
  if (( SECONDS >= deadline )); then
    echo "PostgreSQL 18.6 did not become ready" >&2
    exit 1
  fi
  sleep 1
done

docker_api 20s run -d --name "$ready_container" --network "$network" \
  --memory=640m --cpus=0.60 --pids-limit=256 -p 127.0.0.1::8080 \
  -e FUNDS_DB_JDBC_URL="jdbc:postgresql://${postgres_container}:5432/funds" \
  -e FUNDS_APP_DB_USER=funds_app -e FUNDS_APP_DB_PASSWORD=probe-password \
  "$image" >/dev/null
ready_port="$(docker_api 10s port "$ready_container" 8080/tcp | sed -E 's/.*:([0-9]+)$/\1/')"
deadline=$((SECONDS + 30))
ready_status=""
while (( SECONDS < deadline )); do
  if [[ "$(docker_api 10s inspect -f '{{.State.Running}}' "$ready_container")" != "true" ]]; then
    echo "reachable-database process exited before readiness UP" >&2
    exit 1
  fi
  ready_status="$(curl --silent --show-error --max-time 2 -o "$evidence_dir/ready.json" -w '%{http_code}' \
    "http://127.0.0.1:${ready_port}/q/health/ready" 2>/dev/null || true)"
  [[ "$ready_status" == "200" ]] && break
  sleep 1
done
if [[ "$ready_status" != "200" ]]; then
  echo "reachable PostgreSQL 18.6 did not return exact HTTP 200" >&2
  exit 1
fi
jq -e '.status == "UP" and any(.checks[]; .name == "Database connections health check" and .status == "UP")' \
  "$evidence_dir/ready.json" >/dev/null \
  || { echo "reachable readiness did not pair datasource check with UP" >&2; exit 1; }
echo "reachable-database probe: HTTP 200, aggregate UP, datasource UP (migration validation remains separate)"
