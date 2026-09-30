# syntax=docker/dockerfile:1
#
# Build context is the repository root (this service's pom.xml depends on the root
# reactor pom and the sibling service pom.xml files just to resolve the module tree,
# not on any sibling source code — see docs/ARCHITECTURE.md).
#   docker build -f services/inventory-service/Dockerfile -t sagaharbor/inventory-service .
#
# The base images are pinned to an immutable multi-arch index digest (not just the
# 21-jdk/21-jre-noble tag) so a rebuild always resolves to the exact same base — the
# tag is kept alongside the digest only as a human-readable hint. Refresh both digests
# together when bumping the JDK.

FROM eclipse-temurin:24-jdk-noble@sha256:dacac8e9a0df0d2bd24e702b4431132875c249930b70555ebd7ca285b5bee684 AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY contracts/pom.xml contracts/pom.xml
COPY services/order-service/pom.xml services/order-service/pom.xml
COPY services/inventory-service/pom.xml services/inventory-service/pom.xml
COPY services/payment-service/pom.xml services/payment-service/pom.xml
COPY services/fulfillment-service/pom.xml services/fulfillment-service/pom.xml
RUN ./mvnw -q -pl services/inventory-service -am dependency:go-offline

COPY services/inventory-service/src services/inventory-service/src
RUN ./mvnw -q -pl services/inventory-service -am package -DskipTests

FROM eclipse-temurin:24-jre-noble@sha256:b416d02335e702b0403ff280de9475a3348e29382285969c9d4e17862ce632e7

# Supplied by the build (CI passes the real values); the defaults keep a bare
# `docker build` working locally. These only feed the OCI labels below — no secret
# is ever passed as a build argument.
ARG SERVICE_VERSION=0.1.0-SNAPSHOT
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="SagaHarbor Inventory Service" \
      org.opencontainers.image.description="Product stock, race-safe reservations, releases, and stock adjustments." \
      org.opencontainers.image.version="${SERVICE_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.vendor="SagaHarbor" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:21-jre-noble"

RUN useradd --system --create-home --shell /usr/sbin/nologin sagaharbor
USER sagaharbor
WORKDIR /app
COPY --from=build /workspace/services/inventory-service/target/inventory-service.jar app.jar

EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8082/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
