package dev.sagaharbor.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sagaharbor.order.config.TestSecurityConfig;
import dev.sagaharbor.order.config.TestcontainersConfiguration;
import dev.sagaharbor.order.domain.IncidentKind;
import dev.sagaharbor.order.domain.IncidentStatus;
import dev.sagaharbor.order.domain.OperationsIncident;
import dev.sagaharbor.order.domain.OperationsIncidentRepository;
import dev.sagaharbor.order.domain.Order;
import dev.sagaharbor.order.domain.OrderCancellationReasonCode;
import dev.sagaharbor.order.domain.OrderOperationsProjection;
import dev.sagaharbor.order.domain.OrderOperationsProjectionRepository;
import dev.sagaharbor.order.domain.OrderRepository;
import dev.sagaharbor.order.domain.OrderStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Covers reconciliation directly against real Postgres/Kafka: a cancellation stuck past its
 * threshold gets exactly one safe retry before escalating to REQUIRES_REVIEW, even when an operator
 * has acknowledged the stuck-cancellation incident in between; an escalated order is left to its
 * operator; a happy-path stage past its own threshold gets one SLA_BREACHED incident per stage
 * visit and keeps its status; and the advisory lock actually stops two concurrent reconcile() calls
 * from double-processing the same stuck order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class ReconciliationServiceIT {

  @Autowired private ReconciliationService reconciliationService;
  @Autowired private OrderCancellationTransaction cancellationTransaction;
  @Autowired private OrderRepository orderRepository;
  @Autowired private OrderOperationsProjectionRepository projectionRepository;
  @Autowired private OperationsIncidentRepository incidentRepository;
  @Autowired private IncidentActionService incidentActionService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  void aStuckCancellationGetsOneRetryThenEscalatesOnASecondStuckPass() {
    Order order = seedOrder(OrderStatus.PAYMENT_AUTHORIZED);
    cancellationTransaction.startOrMerge(
        order.getOrderId(),
        "customer",
        "changed their mind",
        OrderCancellationReasonCode.CUSTOMER_REQUESTED,
        true,
        true,
        false,
        UUID.randomUUID(),
        null,
        true);
    ageCancellationRequestedAt(order.getOrderId(), Duration.ofMinutes(11));

    reconciliationService.reconcile();

    assertThat(status(order.getOrderId())).isEqualTo(OrderStatus.CANCELLATION_PENDING);
    assertThat(openIncident(order.getOrderId(), IncidentKind.CANCELLATION_STUCK)).isPresent();

    // Still stuck (the retry didn't actually get confirmed by anything in this test), and the
    // CANCELLATION_STUCK incident from the first pass is still open, so the second pass escalates.
    ageCancellationRequestedAt(order.getOrderId(), Duration.ofMinutes(11));
    reconciliationService.reconcile();

    assertThat(status(order.getOrderId())).isEqualTo(OrderStatus.REQUIRES_REVIEW);
    assertThat(openIncident(order.getOrderId(), IncidentKind.COMPENSATION_EXHAUSTED)).isPresent();
    assertThat(incidentRepository.findAll())
        .filteredOn(
            incident ->
                incident.getOrderId().equals(order.getOrderId())
                    && incident.getKind() == IncidentKind.CANCELLATION_STUCK)
        .as("the first pass's incident must be reused, not duplicated, on the second pass")
        .hasSize(1);
  }

  @Test
  void anAcknowledgedStuckCancellationIncidentStillCountsAsTheOneRetry() {
    Order order = seedOrder(OrderStatus.PAYMENT_AUTHORIZED);
    cancellationTransaction.startOrMerge(
        order.getOrderId(),
        "customer",
        "changed their mind",
        OrderCancellationReasonCode.CUSTOMER_REQUESTED,
        true,
        true,
        false,
        UUID.randomUUID(),
        null,
        true);
    ageCancellationRequestedAt(order.getOrderId(), Duration.ofMinutes(11));
    reconciliationService.reconcile();
    OperationsIncident stuck =
        openIncident(order.getOrderId(), IncidentKind.CANCELLATION_STUCK).orElseThrow();

    // Acknowledging the incident must not give the order a second retry on the next pass.
    incidentActionService.acknowledge(stuck.getIncidentId(), "operator-1");
    ageCancellationRequestedAt(order.getOrderId(), Duration.ofMinutes(11));
    reconciliationService.reconcile();

    assertThat(status(order.getOrderId())).isEqualTo(OrderStatus.REQUIRES_REVIEW);
    assertThat(openIncident(order.getOrderId(), IncidentKind.COMPENSATION_EXHAUSTED)).isPresent();
  }

  @Test
  void aStagePastItsThresholdGetsAnSlaIncidentAndKeepsItsStatus() {
    Order order = seedOrder(OrderStatus.INVENTORY_RESERVED);
    ageOrderUpdatedAt(order.getOrderId(), Duration.ofMinutes(31));

    reconciliationService.reconcile();

    assertThat(status(order.getOrderId())).isEqualTo(OrderStatus.INVENTORY_RESERVED);
    assertThat(openIncident(order.getOrderId(), IncidentKind.SLA_BREACHED)).isPresent();
  }

  @Test
  void aWarehouseStageWithinItsOwnThresholdIsLeftAlone() {
    // 31 minutes is past the old single threshold, but PICKING allows 4 hours.
    Order order = seedOrder(OrderStatus.PICKING);
    ageOrderUpdatedAt(order.getOrderId(), Duration.ofMinutes(31));

    reconciliationService.reconcile();

    assertThat(status(order.getOrderId())).isEqualTo(OrderStatus.PICKING);
    assertThat(incidentsFor(order.getOrderId())).isEmpty();
  }

  @Test
  void aBreachIsReportedOncePerStageVisitEvenAfterItsIncidentIsResolved() {
    Order order = seedOrder(OrderStatus.DISPATCHED);
    ageOrderUpdatedAt(order.getOrderId(), Duration.ofHours(49));
    reconciliationService.reconcile();
    OperationsIncident breach =
        openIncident(order.getOrderId(), IncidentKind.SLA_BREACHED).orElseThrow();

    incidentActionService.resolve(breach.getIncidentId(), "operator-1", "carrier delayed");
    reconciliationService.reconcile();

    assertThat(status(order.getOrderId())).isEqualTo(OrderStatus.DISPATCHED);
    assertThat(incidentsFor(order.getOrderId())).hasSize(1);
  }

  @Test
  void anOrderAlreadyEscalatedToReviewIsNotProcessedAgain() {
    Order order = seedOrder(OrderStatus.PAYMENT_AUTHORIZED);
    cancellationTransaction.startOrMerge(
        order.getOrderId(),
        "customer",
        "changed their mind",
        OrderCancellationReasonCode.CUSTOMER_REQUESTED,
        true,
        true,
        false,
        UUID.randomUUID(),
        null,
        true);
    ageCancellationRequestedAt(order.getOrderId(), Duration.ofMinutes(11));
    reconciliationService.reconcile();
    ageCancellationRequestedAt(order.getOrderId(), Duration.ofMinutes(11));
    reconciliationService.reconcile();
    assertThat(status(order.getOrderId())).isEqualTo(OrderStatus.REQUIRES_REVIEW);
    long requestsBefore = cancellationRequestsFor(order.getOrderId());
    double escalationsBefore = escalationCount();

    ageCancellationRequestedAt(order.getOrderId(), Duration.ofMinutes(11));
    reconciliationService.reconcile();

    assertThat(cancellationRequestsFor(order.getOrderId())).isEqualTo(requestsBefore);
    assertThat(escalationCount())
        .as("an order already waiting for an operator must not be escalated again on every pass")
        .isEqualTo(escalationsBefore);
  }

  private double escalationCount() {
    return meterRegistry.counter("reconciliation.recovery.outcome", "outcome", "escalated").count();
  }

  @Test
  void concurrentReconcileCallsNeverBothActOnTheSameStuckOrder() throws Exception {
    Order order = seedOrder(OrderStatus.PICKING);
    ageOrderUpdatedAt(order.getOrderId(), Duration.ofHours(5));

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> futures =
          IntStream.range(0, 2)
              .<Future<?>>mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            ready.countDown();
                            go.await();
                            reconciliationService.reconcile();
                            return null;
                          }))
              .toList();
      ready.await();
      go.countDown();
      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      pool.shutdown();
    }

    assertThat(status(order.getOrderId())).isEqualTo(OrderStatus.PICKING);
    assertThat(incidentsFor(order.getOrderId()))
        .as("only one of the two concurrent runs may have actually processed this order")
        .hasSize(1);
  }

  private Order seedOrder(OrderStatus status) {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "USD", new BigDecimal("10.00"));
    order.updateStatus(status);
    orderRepository.save(order);
    // advanceStage assumes onOrderPlaced already created this row, exactly like production's
    // OrderCreationTransaction always does — seed it directly here since this test bypasses that.
    projectionRepository.save(
        new OrderOperationsProjection(
            order.getOrderId(),
            order.getCustomerId(),
            status,
            order.getCurrencyCode(),
            order.getTotalAmount(),
            order.getCreatedAt()));
    return order;
  }

  private OrderStatus status(UUID orderId) {
    return orderRepository.findById(orderId).orElseThrow().getStatus();
  }

  private List<OperationsIncident> incidentsFor(UUID orderId) {
    return incidentRepository.findByOrderId(orderId);
  }

  private long cancellationRequestsFor(UUID orderId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
        Long.class,
        orderId,
        "OrderCancellationRequested");
  }

  private Optional<OperationsIncident> openIncident(UUID orderId, IncidentKind kind) {
    return incidentRepository.findByOrderIdAndKindAndStatus(orderId, kind, IncidentStatus.OPEN);
  }

  private void ageOrderUpdatedAt(UUID orderId, Duration age) {
    jdbcTemplate.update(
        "UPDATE orders SET updated_at = ? WHERE order_id = ?",
        Timestamp.from(Instant.now().minus(age)),
        orderId);
  }

  private void ageCancellationRequestedAt(UUID orderId, Duration age) {
    jdbcTemplate.update(
        "UPDATE order_cancellation SET requested_at = ? WHERE order_id = ?",
        Timestamp.from(Instant.now().minus(age)),
        orderId);
  }
}
