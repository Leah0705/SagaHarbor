#!/usr/bin/env bash
# Stages a valid OrderPlaced envelope on Inventory's DLT to model a resolved
# transient delivery failure. ADMIN replays the stored bytes; the test then
# verifies the consumer reserved stock exactly once and emitted InventoryReserved.
# This tests replay and Inventory-side recovery, not the original cause of a DLT.
# The order ID is synthetic and has no Order Service row. With all services running,
# follow-up events can enter Order's DLT; this scenario verifies Inventory only.
# Set USE_RUNNING_INVENTORY=1 to target an already running service (for example,
# a kind port-forward). Set STAGE_ONLY=1 to leave the event pending for a browser demo.
set -euo pipefail
cd "$(dirname "$0")/../.."

if [ ! -f .env ]; then
  echo "Missing .env — run: cp .env.example .env" >&2
  exit 1
fi
set -a
source .env
set +a

for name in sagaharbor-postgres sagaharbor-kafka sagaharbor-redis sagaharbor-keycloak; do
  status=$(docker inspect --format='{{.State.Health.Status}}' "$name" 2>/dev/null || echo missing)
  if [ "$status" != healthy ]; then
    echo "$name is not healthy; start the Compose infrastructure first" >&2
    exit 1
  fi
done

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT

if [ "${USE_RUNNING_INVENTORY:-0}" != 1 ]; then
  SPRING_PROFILES_ACTIVE=local ./mvnw -q -pl services/inventory-service spring-boot:run \
    >/tmp/inventory-service-replay-scenario.log 2>&1 &
  PIDS+=("$!")
fi
READY=0
for _ in $(seq 1 60); do
  if curl -sf "http://localhost:${INVENTORY_SERVICE_PORT}/actuator/health/readiness" >/dev/null; then
    READY=1
    break
  fi
  sleep 2
done
if [ "$READY" != 1 ]; then
  echo "inventory-service did not become ready; see /tmp/inventory-service-replay-scenario.log" >&2
  exit 1
fi

token_for() {
  curl -sf -X POST "$OIDC_ISSUER_URI/protocol/openid-connect/token" \
    -d "grant_type=password" -d "client_id=$OIDC_CLI_CLIENT_ID" \
    -d "client_secret=$OIDC_CLI_CLIENT_SECRET" \
    -d "username=$1" -d "password=$2" \
    -d "scope=openid sagaharbor-api" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])"
}
ADMIN_TOKEN=$(token_for admin.demo "AdminDemo!123")
OPERATOR_TOKEN=$(token_for operator.demo "OperatorDemo!123")
BASE_URL="http://localhost:${INVENTORY_SERVICE_PORT}"

SKU="FAILURE-REPLAY-$(date +%s)-$RANDOM"
echo "== Creating stocked fictional SKU $SKU =="
curl -sf -X POST "$BASE_URL/api/v1/products" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Idempotency-Key: replay-create-$SKU" \
  -H "Content-Type: application/json" \
  -d "{\"sku\":\"$SKU\",\"name\":\"Replay Demo Widget\",\"description\":\"replay verification\"}" \
  >/dev/null
curl -sf -X POST "$BASE_URL/api/v1/inventory/$SKU/adjustments" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Idempotency-Key: replay-restock-$SKU" \
  -H "Content-Type: application/json" \
  -d '{"changeQuantity":10,"reasonCode":"RESTOCK","reasonDetail":"replay verification"}' \
  >/dev/null

ENVELOPE=$(python3 - "$SKU" <<'PY'
import datetime
import json
import sys
import uuid

new_id = lambda: str(uuid.uuid4())
sku = sys.argv[1]
print(json.dumps({
    "eventId": new_id(),
    "eventType": "OrderPlaced",
    "eventVersion": 1,
    "occurredAt": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
    "correlationId": new_id(),
    "causationId": None,
    "aggregateId": new_id(),
    "producer": "order-service",
    "payload": {
        "customerId": new_id(),
        "idempotencyKey": "replay-verification-" + sku,
        "items": [{"sku": sku, "quantity": 1, "unitPrice": {"currencyCode": "USD", "amount": "9.99"}}],
        "totalAmount": {"currencyCode": "USD", "amount": "9.99"}
    }
}, separators=(",", ":")))
PY
)
EVENT_ID=$(printf '%s' "$ENVELOPE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["eventId"])')
ORDER_ID=$(printf '%s' "$ENVELOPE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["aggregateId"])')

echo "== Staging event $EVENT_ID on the Inventory DLT =="
printf '%s\n' "$ENVELOPE" \
  | docker exec -i sagaharbor-kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 --topic sagaharbor.order.events-inventory-dlt >/dev/null

db_value() {
  docker exec sagaharbor-postgres psql -U "$POSTGRES_SUPERUSER" -d inventory_db -tAc "$1"
}
RECORDED=0
for _ in $(seq 1 20); do
  count=$(db_value "SELECT count(*) FROM dead_letter_event WHERE event_id = '$EVENT_ID' AND status = 'PENDING_REVIEW'")
  if [ "$count" = 1 ]; then
    RECORDED=1
    break
  fi
  sleep 2
done
if [ "$RECORDED" != 1 ]; then
  echo "staged event did not reach the persisted dead-letter table" >&2
  exit 1
fi

SNAPSHOT=$(curl -sf "$BASE_URL/api/v1/ops/messaging" -H "Authorization: Bearer $OPERATOR_TOKEN")
printf '%s' "$SNAPSHOT" | EVENT_ID="$EVENT_ID" python3 -c 'import os,sys,json; rows=json.load(sys.stdin)["deadLetters"]; matches=[row for row in rows if row["eventId"]==os.environ["EVENT_ID"]]; assert matches and "envelopeJson" not in matches[0]'
echo "  OPERATOR can see routing metadata without the stored message body"

REPLAY_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  "$BASE_URL/api/v1/admin/dead-letters/$EVENT_ID/replay" \
  -H "Authorization: Bearer $OPERATOR_TOKEN")
if [ "$REPLAY_STATUS" != 403 ]; then
  echo "expected OPERATOR replay to return 403, got $REPLAY_STATUS" >&2
  exit 1
fi

if [ "${STAGE_ONLY:-0}" = 1 ]; then
  printf 'STAGED_EVENT_ID=%s\nSTAGED_ORDER_ID=%s\nSTAGED_SKU=%s\n' "$EVENT_ID" "$ORDER_ID" "$SKU"
  exit 0
fi

REPLAY=$(curl -sf -X POST "$BASE_URL/api/v1/admin/dead-letters/$EVENT_ID/replay" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
printf '%s' "$REPLAY" | EVENT_ID="$EVENT_ID" python3 -c 'import os,sys,json; item=json.load(sys.stdin); assert item["eventId"]==os.environ["EVENT_ID"] and item["status"]=="REPLAYED"'
echo "  ADMIN replay accepted"

RECOVERED=0
for _ in $(seq 1 30); do
  reservation=$(db_value "SELECT count(*) FROM inventory_reservation WHERE order_id = '$ORDER_ID'")
  inbox=$(db_value "SELECT count(*) FROM inbox_event WHERE event_id = '$EVENT_ID' AND consumer_name = 'inventory-service.order-events'")
  outbox=$(db_value "SELECT count(*) FROM outbox_event WHERE aggregate_id = '$ORDER_ID' AND event_type = 'InventoryReserved'")
  if [ "$reservation" = 1 ] && [ "$inbox" = 1 ] && [ "$outbox" = 1 ]; then
    RECOVERED=1
    break
  fi
  sleep 2
done
if [ "$RECOVERED" != 1 ]; then
  echo "replay did not produce one reservation, inbox record, and InventoryReserved event" >&2
  exit 1
fi
stock=$(db_value "SELECT available_quantity FROM stock_level WHERE sku = '$SKU'")
if [ "$stock" != 9 ]; then
  echo "expected 9 available units after replay, got $stock" >&2
  exit 1
fi
echo "== Replay passed: one reservation, one inbox record, one InventoryReserved event, stock 10 -> 9 =="
