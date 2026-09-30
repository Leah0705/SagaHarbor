# Security

SagaHarbor uses JWT authentication, role and ownership checks, environment-based configuration, and
structured error responses. Every credential, user, and payment amount in the local demo is fictional.

## Authentication

- Every service is a native **Spring Security OAuth2 Resource Server** (not a Keycloak adapter). It
  validates a bearer **JWT** on each request; it never issues tokens.
- Tokens are issued by a local **Keycloak** realm (`sagaharbor`) over OIDC. The realm, its three
  fictional demo users, and its clients are in `infra/keycloak/realm-export.json`.
- Validation checks the **issuer** (`OIDC_ISSUER_URI`) and a required **audience**: the token must
  carry `sagaharbor-api`, or it is rejected. Keycloak's `realm_access.roles` claim maps to Spring
  `ROLE_*` authorities.
- Public endpoints (no token): `GET /actuator/health/**`, `/actuator/info`, and
  `/actuator/prometheus`. Everything under `/api/**` requires a valid token.

## Authorization

Three roles, enforced both by URL rules in each `SecurityConfig` and by ownership checks in the
service layer:

| Role | Can do |
|---|---|
| `CUSTOMER` | Place orders, read/track **their own** orders, request cancellation of their own order. A non-owner gets `404`, not `403`, so they cannot distinguish "not yours" from "doesn't exist". |
| `OPERATOR` | Work the fulfillment queue and incident lifecycle; advance fulfillments; inspect messaging backlog and dead-letter metadata; cancel before dispatch. |
| `ADMIN` | Operator actions plus refunds, inventory adjustments, dead-letter replay, and operations-projection rebuild. |

Command endpoints that change state require an `Idempotency-Key` (order/refund/adjustment) or an
`If-Match` version (fulfillment), so retries and races cannot double-apply.

## Data protection

- **No real payment or personal data, ever.** No card number, bank detail, or SSN is accepted,
  logged, or stored anywhere. The payment service is a deterministic simulator driven only by the
  order amount.
- **No secret is logged.** Structured logs carry `correlationId`, `eventId`, `aggregateId`,
  `traceId`/`spanId`, and a safe error classification — never a token or a customer-data payload.
- **No cross-service data access.** No service reads or writes another service's tables; data moves
  only as events, and payment events carry no card-shaped fields (see
  [`docs/EVENT_CATALOG.md`](docs/EVENT_CATALOG.md)).

## Secrets and configuration

- All credentials in this repo (Keycloak users, database passwords, Redis password, client secrets)
  are fictional and local-only. None is a real credential.
- Services read database/Kafka/Redis/OIDC settings from **environment variables with no defaults** —
  a missing variable fails startup with a clear error rather than silently using a production-looking
  value. `.env` is git-ignored; only the fictional `.env.example` is committed.
- In Kubernetes, passwords come from a Secret created by the local deployment script; the committed
  `secret.example.yaml` holds placeholders and is excluded from the Kustomize build.

## Error handling

All HTTP errors use **RFC 9457 Problem Details**. A generic 500 returns a fixed, non-leaking message;
the real exception is logged server-side only. No stack trace or secret is ever returned to a caller.

## Console (browser)

The ops console signs in with **Authorization Code + PKCE** against Keycloak. Tokens are held in
memory, **never in `localStorage`**, so they are not readable by injected script or left behind after
the tab closes. Service container images run as a **non-root** user.

## Threat-model summary

A lightweight STRIDE pass over the parts that matter here:

| Threat | Example | Mitigation |
|---|---|---|
| **Spoofing** | Caller forges identity | JWT signature + issuer + `sagaharbor-api` audience validation on every request |
| **Tampering** | Client sets its own price/total or customer id | Server computes totals from SKU/quantity/price; request body has no `customerId`/`totalAmount` |
| **Repudiation** | "I didn't make that change" | Append-only status/adjustment history with actor, reason, correlation id, timestamp |
| **Information disclosure** | Card/PII leak, non-owner reads an order | No card/PII fields exist; non-owner reads return `404`; no secrets in logs or Problem Details |
| **Denial of service** | Retry storm, poison message, hot-SKU contention | Bounded retry + DLT per consumer; `SELECT ... FOR UPDATE` serializes hot-SKU contention; idempotency keys |
| **Elevation of privilege** | Customer calls an admin endpoint | Role rules in `SecurityConfig` **and** service-layer ownership checks, verified by authorization tests |

## Reporting a concern

Open a GitHub issue with a reproducible description of any authorization, data-isolation, or
credential-handling concern. The local deployment and test boundaries are summarized in
[`docs/PROJECT_SCOPE.md`](docs/PROJECT_SCOPE.md).
