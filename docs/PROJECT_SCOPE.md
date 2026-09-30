# Implementation and verification scope

## Application

SagaHarbor follows an order through Order, Inventory, Payment, and Fulfillment services. Each service owns a PostgreSQL database and exchanges versioned Kafka events. The React console presents operational views for the event-driven workflow.

The Payment service uses a deterministic simulator driven by documented demo amounts. Customer accounts, products, orders, warehouses, credentials, and payment outcomes in the repository are fictional.

The console includes seven operator routes. Refunds and stock adjustments are ADMIN HTTP actions. The Messaging route shows the latest 50 pending dead letters per reachable service, plus each service's unpublished outbox count. OPERATOR can inspect event metadata; ADMIN can request replay of the stored event.

## Local deployment

Docker Compose runs the dependencies and complete demo. A local kind cluster has hosted and smoke-tested the four backend services, with PostgreSQL, Kafka, Redis, and Keycloak running in Compose. The kind overlay uses one replica per service. The base includes NetworkPolicy manifests for clusters with a policy-capable CNI.

## Recovery evidence

The live browser sequence in [`DEMO.md`](DEMO.md#where-evidence-is-stored) checks role-specific Messaging controls and Inventory replay. Its staged event uses a synthetic order ID. Verification follows the persisted dead-letter row, processed inbox row, stock reservation, and `InventoryReserved` outbox event. This evidence describes the Inventory stage of recovery.

The failure scripts exercise Kafka outage and outbox draining, Redis fallback, payment circuit breaking, duplicate delivery, poison-message persistence, and stuck-order reconciliation. [`TESTING.md`](TESTING.md) records the integration, coverage, browser, and local k6 checks.

## Observability

The Compose stack configures Prometheus metrics, Grafana dashboards, and Tempo traces. The repository includes console screenshots; a running local stack supplies the live metrics and traces used during incident investigation.
