# Local infrastructure

SagaHarbor packages the application for two local workflows: Docker Compose runs the complete demo stack, and Kubernetes kind runs the four backend services. Both use the fictional credentials in `.env.example`.

## Docker Compose

`infra/compose/docker-compose.yml` starts PostgreSQL, Kafka, Redis, Keycloak, Prometheus, Tempo, and Grafana. The demo overlay adds the four Spring Boot services. From the repository root:

```bash
cp .env.example .env
make infra-up
make demo-up
make infra-status
```

Grafana is available at `http://localhost:3000`. The applications expose readiness and liveness probes and export metrics and traces through the local observability stack.

## Kubernetes kind

`infra/kubernetes/base/` contains a Deployment and Service for each backend, plus a ConfigMap, PodDisruptionBudgets, and NetworkPolicy manifests. The `kind` overlay loads locally built images and runs one replica of each service on a development machine. PostgreSQL, Kafka, Redis, and Keycloak continue to run in Compose; pods reach them through `host.docker.internal`.

The Deployments run as a non-root user with a read-only root filesystem, dropped Linux capabilities, startup/readiness/liveness probes, and CPU and memory bounds. The base requests two replicas; the local overlay requests one. NetworkPolicy enforcement requires a policy-capable CNI; kind's default kindnet renders these manifests without enforcing traffic rules.

```bash
make infra-up
make kind-up
kubectl -n sagaharbor get deployments,services,pods
make kind-down
```

`make kind-up` builds and loads the four images, applies the manifests, waits for rollout, and checks readiness. `make kind-down` removes the local cluster while retaining the Compose data volumes.

## Files

| Path | Contents |
|---|---|
| `compose/` | Infrastructure, observability, and application demo overlays |
| `kubernetes/base/` | Reusable application manifests |
| `kubernetes/overlays/kind/` | Local image tags and replica counts |
| `keycloak/realm-export.json` | Fictional demo users and roles |
