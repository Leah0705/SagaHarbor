# Known limitations

SagaHarbor is deliberately honest about where its boundaries are. Nothing below is a hidden gap —
each is a scope decision, an environment constraint, or a known defect with a planned fix.

## Correctness and semantics

- **Delivery is at least once, not exactly once.** Kafka redelivers; correctness comes from
  idempotent consumers (an inbox keyed by `(event_id, consumer_name)`) and database constraints. The
  project never claims exactly-once processing. See
  [ARCHITECTURE.md](ARCHITECTURE.md#at-least-once-delivery-and-idempotency).
- **Payment is a deterministic simulator.** There is no real payment gateway. Outcomes are driven by
  seeded, documented "magic" amounts (the convention real card-processor sandboxes use). No card
  number, bank detail, or SSN is ever accepted, logged, or stored. See
  [`../SECURITY.md`](../SECURITY.md).
- **Only event schema `v1` exists**, so a real cross-version compatibility test (does a future `v2`
  still accept everything `v1` did) is not yet written. Stated in
  [`../contracts/README.md`](../contracts/README.md).
- **Reconciliation and SLA thresholds are demo defaults**, labeled as such in configuration, not
  tuned production values.
- **Outbox relay ordering.** Running more than one outbox relay can publish an event twice, and a
  publish that fails and backs off lets later events for the same order overtake it. The
  [README](../README.md#known-limitations) gives the cause and the planned fix. Two other defects
  found in the same review, a cancelled `PENDING` order going uncompensated and one reconciliation
  threshold for every stage, are fixed.

## Product surface

- The console covers seven operator routes. **Refunds and inventory adjustments are ADMIN HTTP commands
  only** — they have no console screen yet.
- The Messaging route lists the latest 50 pending dead letters per reachable service. Counts include
  all pending events, and an unavailable service is shown as unavailable rather than as zero.
  OPERATOR receives event metadata only; an accepted replay request does not prove downstream
  recovery.
- There is no customer-facing web UI; customers are represented by the Order API and demo scripts.

## Deployment and infrastructure

- **The services are not deployed to any cloud.** Local runs use the host (`make run-*`),
  the Compose demo (`make demo-up`), or a local kind cluster for the four backends.
- **The kind deployment uses Compose-hosted stateful services.** All four backends have been
  deployed and smoke-tested on kind; PostgreSQL, Kafka, Redis, and Keycloak stay outside the
  cluster. The kind overlay runs one replica per service on a development machine. The AWS
  Terraform has not been applied and provisions nothing. See [`../infra/README.md`](../infra/README.md).
- kind's default CNI (kindnet) does not enforce NetworkPolicy, so the included NetworkPolicies are
  enforced only on a policy-capable CNI (Calico/Cilium).
- Local Kafka and Redis are single-node. Postgres is one instance with a database per service.

## Evidence and observability

- **The coverage gate is a conservative floor, not a measured figure.** Most coverage here comes from
  the Testcontainers integration suite, so a meaningful number requires running `./mvnw -B verify`
  with Docker available. See [`TESTING.md`](TESTING.md).
- **k6 latency numbers are sandbox figures, not capacity claims** — see
  [`TESTING.md`](TESTING.md#performance-k6--measured-with-limits-stated).
- **Grafana and distributed-trace screenshots are not committed.** Capturing them requires the full
  observability stack. Console screenshots 01–11 show live services and fictional seeded data.
  Screenshot 11 also shows downstream Order dead letters caused by replaying a synthetic order
  without a matching Order Service record. Capture instructions are in [`DEMO.md`](DEMO.md).
