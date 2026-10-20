#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Every docker compose call below, and the stack it starts, uses compose.yaml plus the chosen composition.
COMPOSITION=${COMPOSITION:-load-test}
export COMPOSE_PATH_SEPARATOR=:
export COMPOSE_FILE=compose.yaml:compose.$COMPOSITION.yaml

ALL_ENDPOINTS=(create-slot get-slot list-slots block-view reschedule delete-slot book edit-meeting cancel-meeting)
if [ $# -gt 0 ]; then ENDPOINTS=("$@"); else ENDPOINTS=("${ALL_ENDPOINTS[@]}"); fi

DURATION=${DURATION:-3m}
USERS=${USERS:-500}
RACE_RATE=${RACE_RATE:-100}
APP_URL=${APP_URL:-http://localhost:${APP_PORT:-8080}}
TEST_ID=$(date -u +%Y%m%dT%H%M%SZ)
OUT=load-test/results/$TEST_ID
export K6_UID=$(id -u)
mkdir -p "$OUT"

psql() {
  docker compose exec -T postgres psql -U postgres -d slots -At "$@"
}

server_errors() {
  curl -s "$APP_URL/actuator/prometheus" \
    | awk '/^http_server_requests_seconds_count\{/ && /status="5[0-9][0-9]"/ { sum += $NF } END { printf "%d", sum }'
}

invariants() {
  psql -F ' | ' -c "
    select
      (select count(*) from slot),
      (select count(*) from slot where title is not null),
      (select count(*) from slot a join slot b on a.user_id = b.user_id and a.id < b.id
         and tstzrange(a.begin_at, a.end_at, '[)') && tstzrange(b.begin_at, b.end_at, '[)')),
      (select count(*) from slot where title is not null and status <> 'BUSY'),
      (select count(*) from slot where title is null and (description is not null or participants <> '{}'))"
}

docker compose up -d --build --wait

echo "Test $TEST_ID ($COMPOSITION): ${#ENDPOINTS[@]} endpoints, $USERS users + $RACE_RATE race interactions/s, $DURATION each"
for endpoint in "${ENDPOINTS[@]}"; do
  psql -q -c "truncate slot"
  errors_before=$(server_errors)
  started=$(date -u +%H:%M:%S)
  docker compose --profile load-test run --rm --no-deps \
    -e ENDPOINT="$endpoint" -e DURATION="$DURATION" -e USERS="$USERS" -e RACE_RATE="$RACE_RATE" \
    -e TEST_ID="$TEST_ID" -e RESULTS_DIR="/results/$TEST_ID" k6 || echo "$endpoint: thresholds failed" >&2
  errors=$(( $(server_errors) - errors_before ))
  echo "| $endpoint | $started | $(invariants) | $errors |" > "$OUT/$endpoint.invariants.md"
done

{
  echo "# Load test $TEST_ID"
  echo
  echo "$USERS concurrent users plus $RACE_RATE race interactions/s against one endpoint at a time, $DURATION per endpoint, composition \`$COMPOSITION\`."
  echo
  echo "## Load: $USERS concurrent users"
  echo
  echo "| Endpoint | Requests | Req/s | Avg ms | p95 ms | p99 ms | Max ms | Statuses | Unexpected |"
  echo "|---|---|---|---|---|---|---|---|---|"
  for endpoint in "${ENDPOINTS[@]}"; do cat "$OUT/$endpoint.load.md" 2>/dev/null || echo "| $endpoint | no result |"; done
  echo
  echo "## Race: $RACE_RATE interactions/s on hot resources"
  echo
  echo "| Endpoint | Interactions | Dropped | Requests | p95 ms | Statuses | Unexpected | Consistent |"
  echo "|---|---|---|---|---|---|---|---|"
  for endpoint in "${ENDPOINTS[@]}"; do cat "$OUT/$endpoint.race.md" 2>/dev/null || echo "| $endpoint | no result |"; done
  echo
  echo "## Database invariants after each endpoint"
  echo
  echo "| Endpoint | Started (UTC) | Slots | Meetings | Overlaps | Meeting not busy | Details without meeting | Server 5xx |"
  echo "|---|---|---|---|---|---|---|---|"
  for endpoint in "${ENDPOINTS[@]}"; do cat "$OUT/$endpoint.invariants.md"; done
} > "$OUT/report.md"

echo "Report: $OUT/report.md"
