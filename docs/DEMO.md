# Five-minute demo

A deterministic walkthrough for a reviewer. The seeded workflow uses the real HTTP APIs and event
choreography; the replay scenario deliberately stages a Kafka message on the DLT. Nothing is written
directly to a database. Payment outcomes are deterministic because the simulator is driven by seeded,
documented amounts. All users, products, orders,
credentials, and financial outcomes are **fictional demo data**.

## 0. Prerequisites (once)

```
cp .env.example .env
make infra-up          # PostgreSQL, Kafka, Redis, Keycloak — waits until healthy
```

## 1. Seed a full, deterministic picture (~1 min)

```
scripts/seed-demo-data.sh
```

This starts the four services against the running infra and, through the real APIs, produces one
order of **every shape the console needs to show**: a delivered order, an inventory-rejected order, a
payment-declined order, a low-stock signal, a plain in-flight order, a `CANCELLATION_AFTER_DISPATCH`
incident, and a `CANCELLATION_STUCK` incident (the last uses the real reconciliation job with
shortened demo thresholds, not a manual edit). It is repeatable and self-contained.

## 2. Log in and walk the console (~2 min)

```
cd apps/ops-console && npm install && npm run dev     # http://localhost:5173
```

Sign in as **`operator.demo`** (password `OperatorDemo!123`, fictional/local-only). Walk the routes —
each is a real screenshot in [`screenshots/`](screenshots/):

1. **Overview** — KPI headline numbers and time series. Every formula is defined in
   [`KPI_DICTIONARY.md`](KPI_DICTIONARY.md).
2. **Work Queue** — an SLA-aware backlog, filterable and CSV-exportable.
3. **Order Detail** — one order's full status timeline, assembled from the events every service
   emitted. The timeline is one story stitched from four independent services' events; no service
   reached into another's database to build it.
4. **Incidents** — the two seeded incidents, each with an acknowledge → assign → resolve lifecycle
   (ADMIN also sees the dead-letter replay panel here).
5. **Inventory Risk** — the SKU that crossed its low-stock threshold, surfaced by an
   `InventoryLowStock.v1` event.
6. **Fulfillment Board** — fulfillments grouped by workflow stage.
7. **Messaging** — per-service dead-letter and outbox backlog. OPERATOR can inspect; ADMIN can
   request a replay of a stored event. A replay request does not by itself prove business recovery.

To advance a fulfillment yourself, open an in-flight order on the Fulfillment Board and move it
through `PICKING → PACKED → DISPATCHED → DELIVERED`; each step emits a `FulfillmentStatusChanged.v1`
event that the order timeline picks up.

## 3. Run one failure-and-recovery scenario (~1 min)

```
tests/failure-scenarios/payment-outage-recovery.sh
```

It drives the payment simulator into repeated technical failures (seeded amount `9998.00`), watches
the Resilience4j circuit breaker trip to **OPEN**, then places a normal order after the wait window
and shows the circuit recover to **CLOSED** with the healthy order authorized. Safe and reversible —
it touches only seeded fictional amounts and never deletes a volume.

Detect → diagnose → recover → verify for each incident type is in
[`OPERATIONS_RUNBOOK.md`](OPERATIONS_RUNBOOK.md). Other committed scenarios under
[`../tests/failure-scenarios/`](../tests/failure-scenarios/) follow the same shape:

| Script | Demonstrates |
|---|---|
| `kafka-outage-outbox-backlog.sh` | Stopping Kafka makes the outbox backlog climb, then drain to zero on recovery. |
| `redis-outage-fallback.sh` | Redis down → the availability cache fails open to PostgreSQL; reads still return correct data. |
| `duplicate-kafka-delivery.sh` | A redelivered event is a no-op (inbox dedup) — no double reservation or payment. |
| `poison-message-dlt.sh` | A non-retryable OrderPlaced payload is persisted as a dead letter; an OPERATOR can inspect it but cannot replay it. |
| `admin-dead-letter-replay.sh` | Stages a valid event on Inventory's dead-letter topic, then checks ADMIN replay and the resulting stock reservation. |
| `stuck-order-reconciliation.sh` | A stuck order is found by the reconciliation job and either nudged or escalated to a `REQUIRES_REVIEW` incident. |

For the Messaging route, run `poison-message-dlt.sh` while the four services are stopped, then
start them and sign in as `operator.demo`. The inventory row remains pending for inspection.
The payload is intentionally invalid, so replaying it would fail again. Run
`admin-dead-letter-replay.sh` separately to verify the replay path and business effect with a
valid staged event. It models a resolved transient failure; it does not create the initial failure.
Run `kafka-outage-outbox-backlog.sh` separately to verify that an outbox backlog grows during a broker
outage and drains after Kafka returns. All three scripts read the new operator endpoint against live
services; the normal browser tests still use the console's demo data.

## 4. Follow one order as a distributed trace (optional)

Bring up the observability stack
(`docker compose -f infra/compose/docker-compose.yml up -d prometheus tempo grafana`), place one
order, and open its trace in Grafana → Tempo. An order appears as a single trace across all four
services and every Kafka boundary, because trace context is propagated across the outbox as well as
HTTP and Kafka.

## Reset

```
make infra-down            # stops infra; keeps data. Add DOWN_ARGS=-v to wipe volumes.
```

## Where evidence is stored

- **Console screenshots** — [`screenshots/`](screenshots/): 01 is the local Keycloak login; 02–07
  are clearly labeled demo-mode views. Screenshots 08–11 come from a live browser session against
  four host-run services and Keycloak after the retry/DLT topics were isolated by consumer service.
  `admin-dead-letter-replay.sh` staged event `ee2e76a4-dac5-4a17-a440-2cbc18f63ca5` for order
  `66280ded-3f6c-4433-bdd3-16a75b52b4d1` on Inventory's DLT. OPERATOR saw the event without a
  replay action; ADMIN confirmed replay in the browser, and Inventory's pending count fell from 1
  to 0. Inventory's database then had one `REPLAYED` dead-letter row, one `PROCESSED` inbox row,
  one reservation, and one `InventoryReserved` outbox event. Available stock changed from 10 to 9,
  with 1 unit reserved. This synthetic order has no matching Order Service record, so the check
  proves Inventory recovery only. Its downstream events enter Order's DLT; those rows are visible
  in the live screenshots and are not evidence of full order completion.
  To recapture 02–07, run `npm run screenshots` from `apps/ops-console`. For 01 and 08–11, run
  `USE_RUNNING_INVENTORY=1 STAGE_ONLY=1 tests/failure-scenarios/admin-dead-letter-replay.sh` from
  the repository root, then run
  `SAGAHARBOR_STAGED_ORDER_ID=<printed-order-id> npm run screenshots -- live-messaging.spec.ts`
  from `apps/ops-console`.
- **k6 load-test summaries** — [`evidence/k6/`](evidence/k6/), the raw JSON from
  [`../tests/perf/`](../tests/perf/).
- **Grafana, trace, and failure-recovery captures** are not committed — they require the full
  observability stack running. To capture your own: bring up the stack above, run a scenario, and
  screenshot the relevant Grafana dashboard during the failure, the Tempo trace of one affected
  order, and the dashboard after recovery.

## What a reviewer should take away

- Four independently deployable services coordinating only through **versioned Kafka events**.
- A partial failure is **compensated by choreography** and, when it cannot be auto-resolved, becomes
  an **operator incident** with a real lifecycle.
- Every number on screen has a **documented formula**.
