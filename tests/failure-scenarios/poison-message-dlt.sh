#!/usr/bin/env bash
# Failure demo: publishes a valid EventEnvelope with an invalid OrderPlaced payload
# directly onto sagaharbor.order.events. The consumer rejects it and routes it to its
# dead-letter topic; the DLT handler can still parse the envelope and persist it.
# Confirms an OPERATOR can see the pending row but cannot replay it. Starts only
# inventory-service locally against the running Compose infra and stops it on exit. Safe and
# reversible: only ever appends a message to Kafka and a row to Postgres, nothing is deleted.
set -euo pipefail
cd "$(dirname "$0")/../.."

if [ ! -f .env ]; then
  echo "Missing .env — run: cp .env.example .env" >&2
  exit 1
fi
set -a
source .env
set +a

echo "== Checking infrastructure containers are healthy =="
for name in sagaharbor-postgres sagaharbor-kafka sagaharbor-redis sagaharbor-keycloak; do
  status=$(docker inspect --format='{{.State.Health.Status}}' "$name" 2>/dev/null || echo "missing")
  echo "  $name: $status"
  if [ "$status" != "healthy" ]; then
    echo "$name is not healthy. Run 'make infra-up' and wait for it to settle, then retry." >&2
    exit 1
  fi
done

PIDS=()
cleanup() {
  echo "== Stopping services started by this script =="
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT

echo "== Starting inventory-service (profile: local) on port $INVENTORY_SERVICE_PORT =="
SPRING_PROFILES_ACTIVE=local ./mvnw -q -pl services/inventory-service spring-boot:run \
  >/tmp/inventory-service-failure-scenario.log 2>&1 &
PIDS+=("$!")
for _ in $(seq 1 60); do
  if curl -sf "http://localhost:${INVENTORY_SERVICE_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

dlt_counter() {
  curl -sf "http://localhost:${INVENTORY_SERVICE_PORT}/actuator/prometheus" \
    | awk '/^kafka_consumer_dlt_total/ { total += $NF } END { print total + 0 }'
}
dead_letter_row_count() {
  docker exec sagaharbor-postgres psql -U "$POSTGRES_SUPERUSER" -d inventory_db -tAc \
    "SELECT count(*) FROM dead_letter_event"
}

BEFORE_DLT=$(dlt_counter)
BEFORE_ROWS=$(dead_letter_row_count)
echo "== Baseline: kafka_consumer_dlt_total=$BEFORE_DLT, dead_letter_event rows=$BEFORE_ROWS =="

echo "== Publishing an OrderPlaced envelope with invalid items onto sagaharbor.order.events =="
POISON_ENVELOPE=$(python3 -c 'import json, uuid, datetime; u=lambda: str(uuid.uuid4()); print(json.dumps({"eventId":u(),"eventType":"OrderPlaced","eventVersion":1,"occurredAt":datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00","Z"),"correlationId":u(),"causationId":u(),"aggregateId":u(),"producer":"failure-scenario","payload":{"items":[]}}))')
EVENT_ID=$(printf '%s' "$POISON_ENVELOPE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["eventId"])')
printf '%s\n' "$POISON_ENVELOPE" \
  | docker exec -i sagaharbor-kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 --topic sagaharbor.order.events >/dev/null
echo "  message published"

echo "== Waiting for the non-retryable payload to reach the DLT =="
LANDED=0
for _ in $(seq 1 20); do
  after_rows=$(dead_letter_row_count)
  if [ "$after_rows" -gt "$BEFORE_ROWS" ]; then
    LANDED=1
    break
  fi
  sleep 2
done
if [ "$LANDED" != "1" ]; then
  echo "no new dead_letter_event row appeared after the poison message" >&2
  exit 1
fi
echo "  confirmed: a new dead_letter_event row was persisted (queryable and replayable)"

OPERATOR_TOKEN=$(curl -sf -X POST "$OIDC_ISSUER_URI/protocol/openid-connect/token" \
  -d "grant_type=password" -d "client_id=$OIDC_CLI_CLIENT_ID" \
  -d "client_secret=$OIDC_CLI_CLIENT_SECRET" \
  -d "username=operator.demo" -d "password=OperatorDemo!123" \
  -d "scope=openid sagaharbor-api" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
SNAPSHOT=$(curl -sf "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/ops/messaging" \
  -H "Authorization: Bearer $OPERATOR_TOKEN")
printf '%s' "$SNAPSHOT" | EVENT_ID="$EVENT_ID" python3 -c 'import os,sys,json; data=json.load(sys.stdin); rows=[row for row in data["deadLetters"] if row["eventId"] == os.environ["EVENT_ID"]]; assert data["pendingDeadLetterCount"] > 0 and rows; assert "envelopeJson" not in rows[0]'
echo "  confirmed: OPERATOR sees the pending event through the new API"

REPLAY_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/admin/dead-letters/$EVENT_ID/replay" \
  -H "Authorization: Bearer $OPERATOR_TOKEN")
if [ "$REPLAY_STATUS" != "403" ]; then
  echo "expected OPERATOR replay to be forbidden, got HTTP $REPLAY_STATUS" >&2
  exit 1
fi
echo "  confirmed: OPERATOR cannot replay the event"

AFTER_DLT=$(dlt_counter)
echo "  kafka_consumer_dlt_total now: $AFTER_DLT"
if ! awk "BEGIN{exit !($AFTER_DLT > $BEFORE_DLT)}"; then
  echo "expected kafka_consumer_dlt_total to increase after the poison message" >&2
  exit 1
fi

echo "== Failure scenario passed: poison message exhausted retries and was safely dead-lettered =="
