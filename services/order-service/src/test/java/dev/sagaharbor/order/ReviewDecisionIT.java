package dev.sagaharbor.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sagaharbor.order.config.TestSecurityConfig;
import dev.sagaharbor.order.config.TestcontainersConfiguration;
import dev.sagaharbor.order.domain.IncidentKind;
import dev.sagaharbor.order.domain.IncidentStatus;
import dev.sagaharbor.order.domain.OperationsIncidentRepository;
import dev.sagaharbor.order.domain.Order;
import dev.sagaharbor.order.domain.OrderOperationsProjection;
import dev.sagaharbor.order.domain.OrderOperationsProjectionRepository;
import dev.sagaharbor.order.domain.OrderRepository;
import dev.sagaharbor.order.domain.OrderReviewDecision;
import dev.sagaharbor.order.domain.OrderStageDuration;
import dev.sagaharbor.order.domain.OrderStageDurationRepository;
import dev.sagaharbor.order.domain.OrderStatus;
import dev.sagaharbor.order.messaging.EventEnvelope;
import dev.sagaharbor.order.messaging.FulfillmentEventsListener;
import dev.sagaharbor.order.messaging.InventoryEventsListener;
import dev.sagaharbor.order.messaging.PaymentEventsListener;
import dev.sagaharbor.order.service.OrderCancellationService;
import dev.sagaharbor.order.service.OrderReviewService;
import dev.sagaharbor.order.service.ReconciliationService;
import dev.sagaharbor.order.service.ReviewDecisionNotAllowedException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Covers the operator's way out of REQUIRES_REVIEW against real Postgres/Kafka: resuming a
 * cancellation requested after dispatch (including re-entering a stage the order already visited,
 * which order_stage_duration's unique (order_id, stage) must allow), resuming from a milestone that
 * arrived during the review, retrying a cancellation that ran out of retries, and refusing a
 * cancellation once the goods have left the warehouse.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class ReviewDecisionIT {

  @Autowired private OrderReviewService orderReviewService;
  @Autowired private OrderCancellationService orderCancellationService;
  @Autowired private ReconciliationService reconciliationService;
  @Autowired private InventoryEventsListener inventoryEventsListener;
  @Autowired private PaymentEventsListener paymentEventsListener;
  @Autowired private FulfillmentEventsListener fulfillmentEventsListener;
  @Autowired private OrderRepository orderRepository;
  @Autowired private OrderOperationsProjectionRepository projectionRepository;
  @Autowired private OrderStageDurationRepository stageDurationRepository;
  @Autowired private OperationsIncidentRepository incidentRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void resumingATurnedDownCancellationReturnsTheOrderToDispatched() {
    Order order = seedOrder(OrderStatus.PACKED);
    advanceFulfillment(order.getOrderId(), "DISPATCHED");
    requestCustomerCancellation(order);
    assertStatus(order.getOrderId(), OrderStatus.REQUIRES_REVIEW);

    orderReviewService.decide(
        order.getOrderId(),
        OrderReviewDecision.RESUME,
        "operator-1",
        "parcel already with the carrier",
        UUID.randomUUID());

    assertStatus(order.getOrderId(), OrderStatus.DISPATCHED);
    OrderStageDuration dispatched =
        stageDurationRepository
            .findByOrderIdAndStage(order.getOrderId(), OrderStatus.DISPATCHED)
            .orElseThrow();
    assertThat(dispatched.getExitedAt()).as("re-entering DISPATCHED reopens its row").isNull();
    assertThat(
            incidentRepository.findByOrderIdAndKindAndStatus(
                order.getOrderId(),
                IncidentKind.CANCELLATION_AFTER_DISPATCH,
                IncidentStatus.RESOLVED))
        .isPresent();

    advanceFulfillment(order.getOrderId(), "DELIVERED");
    assertStatus(order.getOrderId(), OrderStatus.DELIVERED);
  }

  @Test
  void resumeContinuesFromAMilestoneThatArrivedDuringTheReview() {
    Order order = seedOrder(OrderStatus.DISPATCHED);
    requestCustomerCancellation(order);
    advanceFulfillment(order.getOrderId(), "DELIVERED");
    assertStatus(order.getOrderId(), OrderStatus.REQUIRES_REVIEW);

    orderReviewService.decide(
        order.getOrderId(), OrderReviewDecision.RESUME, "operator-1", "delivered", null);

    assertStatus(order.getOrderId(), OrderStatus.DELIVERED);
  }

  @Test
  void cancellingAfterDispatchIsRefused() {
    Order order = seedOrder(OrderStatus.DISPATCHED);
    requestCustomerCancellation(order);

    assertThatThrownBy(
            () ->
                orderReviewService.decide(
                    order.getOrderId(), OrderReviewDecision.CANCEL, "operator-1", "x", null))
        .isInstanceOf(ReviewDecisionNotAllowedException.class);
    assertStatus(order.getOrderId(), OrderStatus.REQUIRES_REVIEW);
  }

  @Test
  void retryingAnExhaustedCancellationFinishesOnceTheLastConfirmationArrives() {
    Order order = seedOrder(OrderStatus.PAYMENT_AUTHORIZED);
    requestCustomerCancellation(order);
    ageCancellationRequestedAt(order.getOrderId(), Duration.ofMinutes(11));
    reconciliationService.reconcile();
    ageCancellationRequestedAt(order.getOrderId(), Duration.ofMinutes(11));
    reconciliationService.reconcile();
    assertStatus(order.getOrderId(), OrderStatus.REQUIRES_REVIEW);

    // Inventory's confirmation arrives while the order waits for review; it is kept.
    inventoryEventsListener.onMessage(inventoryReleased(order.getOrderId()));
    assertStatus(order.getOrderId(), OrderStatus.REQUIRES_REVIEW);

    orderReviewService.decide(
        order.getOrderId(), OrderReviewDecision.CANCEL, "operator-1", "retry", UUID.randomUUID());

    assertStatus(order.getOrderId(), OrderStatus.CANCELLATION_PENDING);
    assertThat(cancellationRequestsFor(order.getOrderId())).isGreaterThanOrEqualTo(3);
    assertThat(List.of(IncidentKind.CANCELLATION_STUCK, IncidentKind.COMPENSATION_EXHAUSTED))
        .allSatisfy(
            kind ->
                assertThat(
                        incidentRepository.findByOrderIdAndKindAndStatusNot(
                            order.getOrderId(), kind, IncidentStatus.RESOLVED))
                    .as(kind.name())
                    .isEmpty());

    paymentEventsListener.onMessage(paymentRefunded(order.getOrderId()));
    assertStatus(order.getOrderId(), OrderStatus.CANCELLED);
  }

  private void requestCustomerCancellation(Order order) {
    orderCancellationService.requestCancellation(
        order.getOrderId(),
        order.getCustomerId().toString(),
        false,
        "review-" + order.getOrderId(),
        null,
        UUID.randomUUID());
  }

  private void advanceFulfillment(UUID orderId, String newStatus) {
    fulfillmentEventsListener.onMessage(
        envelope("FulfillmentStatusChanged", orderId, Map.of("newStatus", newStatus)));
  }

  private String inventoryReleased(UUID orderId) {
    return envelope(
        "InventoryReleased",
        orderId,
        Map.of(
            "reservationId",
            UUID.randomUUID().toString(),
            "items",
            List.of(Map.of("sku", "WIDGET-1", "quantity", 1)),
            "reasonCode",
            "ORDER_CANCELLED"));
  }

  private String paymentRefunded(UUID orderId) {
    return envelope(
        "PaymentRefunded",
        orderId,
        Map.of(
            "paymentId",
            UUID.randomUUID().toString(),
            "amount",
            Map.of("currencyCode", "USD", "amount", "10.00"),
            "reasonCode",
            "ORDER_CANCELLED"));
  }

  private long cancellationRequestsFor(UUID orderId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
        Long.class,
        orderId,
        "OrderCancellationRequested");
  }

  private void ageCancellationRequestedAt(UUID orderId, Duration age) {
    jdbcTemplate.update(
        "UPDATE order_cancellation SET requested_at = ? WHERE order_id = ?",
        Timestamp.from(Instant.now().minus(age)),
        orderId);
  }

  private void assertStatus(UUID orderId, OrderStatus expected) {
    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(expected);
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

  private String envelope(String eventType, UUID orderId, Map<String, Object> payload) {
    var envelope =
        new EventEnvelope(
            UUID.randomUUID(),
            eventType,
            1,
            Instant.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            orderId,
            "test-producer",
            objectMapper.valueToTree(payload));
    return objectMapper.writeValueAsString(envelope);
  }
}
