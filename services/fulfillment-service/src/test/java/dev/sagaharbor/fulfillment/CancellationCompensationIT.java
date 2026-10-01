package dev.sagaharbor.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sagaharbor.fulfillment.config.TestSecurityConfig;
import dev.sagaharbor.fulfillment.config.TestcontainersConfiguration;
import dev.sagaharbor.fulfillment.domain.Fulfillment;
import dev.sagaharbor.fulfillment.domain.FulfillmentRepository;
import dev.sagaharbor.fulfillment.domain.FulfillmentStatus;
import dev.sagaharbor.fulfillment.domain.FulfillmentStatusHistory;
import dev.sagaharbor.fulfillment.domain.FulfillmentStatusHistoryRepository;
import dev.sagaharbor.fulfillment.messaging.EventEnvelope;
import dev.sagaharbor.fulfillment.messaging.OrderCancellationRequestedListener;
import dev.sagaharbor.fulfillment.messaging.PaymentAuthorizedListener;
import dev.sagaharbor.fulfillment.service.FulfillmentCommandService;
import java.time.Instant;
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
 * Covers OrderCancellationRequestedListener: cancels a still-cancellable fulfillment, leaves a
 * marker so a later PaymentAuthorized creates no fulfillment, confirms again for one already
 * cancelled, and is a safe no-op when there's no fulfillment for the order yet or it can no longer
 * be cancelled (DISPATCHED) — Order Service's reconciliation is the safety net for that last case,
 * not an error surfaced here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class CancellationCompensationIT {

  @Autowired private OrderCancellationRequestedListener listener;
  @Autowired private PaymentAuthorizedListener paymentAuthorizedListener;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FulfillmentRepository fulfillmentRepository;
  @Autowired private FulfillmentStatusHistoryRepository statusHistoryRepository;
  @Autowired private FulfillmentCommandService fulfillmentCommandService;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void cancelsAStillCancellableFulfillment() {
    Fulfillment fulfillment = saveNewFulfillment();

    listener.onMessage(
        envelope(
            fulfillment.getOrderId(),
            Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "changed their mind")));

    Fulfillment updated =
        fulfillmentRepository.findById(fulfillment.getFulfillmentId()).orElseThrow();
    assertThat(updated.getStatus()).isEqualTo(FulfillmentStatus.CANCELLED);
    assertThat(updated.getCancellationReasonCode()).isEqualTo("ORDER_CANCELLATION_REQUESTED");
  }

  @Test
  void noFulfillmentYetIsANoOp() {
    listener.onMessage(
        envelope(
            UUID.randomUUID(), Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "n/a")));
    // No exception — nothing exists for this order yet.
  }

  @Test
  void aPaymentAuthorizedAfterTheCancellationRequestCreatesNoFulfillment() {
    UUID orderId = UUID.randomUUID();
    listener.onMessage(
        envelope(orderId, Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "early")));

    paymentAuthorizedListener.onMessage(paymentAuthorized(orderId));

    assertThat(fulfillmentRepository.findByOrderId(orderId)).isEmpty();
    assertThat(outboxEventsFor(orderId, "FulfillmentAssigned")).isZero();
  }

  @Test
  void alreadyCancelledConfirmsAgainWithoutCancellingTwice() {
    Fulfillment fulfillment = saveNewFulfillment();
    fulfillmentCommandService.cancel(
        fulfillment.getFulfillmentId(), 0, "already cancelled", "operator-1", UUID.randomUUID());

    listener.onMessage(
        envelope(
            fulfillment.getOrderId(),
            Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "again")));

    long cancelledHistoryRows =
        statusHistoryRepository
            .findByFulfillmentIdOrderByOccurredAtAsc(fulfillment.getFulfillmentId())
            .stream()
            .filter(row -> row.getStatus() == FulfillmentStatus.CANCELLED)
            .count();
    assertThat(cancelledHistoryRows).isEqualTo(1);
    // Order Service asked again because the first confirmation never reached it.
    assertThat(outboxEventsFor(fulfillment.getOrderId(), "FulfillmentStatusChanged")).isEqualTo(2);
  }

  @Test
  void dispatchedFulfillmentIsNotCancelled() {
    Fulfillment fulfillment = saveNewFulfillment();
    fulfillmentCommandService.advance(
        fulfillment.getFulfillmentId(),
        0,
        "PICKING",
        null,
        null,
        null,
        "operator-1",
        UUID.randomUUID());
    fulfillmentCommandService.advance(
        fulfillment.getFulfillmentId(),
        1,
        "PACKED",
        null,
        null,
        null,
        "operator-1",
        UUID.randomUUID());
    fulfillmentCommandService.advance(
        fulfillment.getFulfillmentId(),
        2,
        "DISPATCHED",
        "TRACK-1",
        null,
        null,
        "operator-1",
        UUID.randomUUID());

    listener.onMessage(
        envelope(
            fulfillment.getOrderId(),
            Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "too late")));

    Fulfillment stillDispatched =
        fulfillmentRepository.findById(fulfillment.getFulfillmentId()).orElseThrow();
    assertThat(stillDispatched.getStatus()).isEqualTo(FulfillmentStatus.DISPATCHED);
  }

  private String paymentAuthorized(UUID orderId) {
    EventEnvelope envelope =
        new EventEnvelope(
            UUID.randomUUID(),
            "PaymentAuthorized",
            1,
            Instant.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            orderId,
            "payment-service",
            objectMapper.valueToTree(
                Map.of(
                    "paymentId",
                    UUID.randomUUID().toString(),
                    "amount",
                    Map.of("currencyCode", "USD", "amount", "10.00"),
                    "precedingTechnicalFailureCount",
                    0)));
    return objectMapper.writeValueAsString(envelope);
  }

  private long outboxEventsFor(UUID orderId, String eventType) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
        Long.class,
        orderId,
        eventType);
  }

  private Fulfillment saveNewFulfillment() {
    Fulfillment fulfillment =
        Fulfillment.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "WH-ATL-1",
            Instant.now().plusSeconds(3600),
            UUID.randomUUID());
    fulfillmentRepository.save(fulfillment);
    statusHistoryRepository.save(
        new FulfillmentStatusHistory(
            fulfillment.getFulfillmentId(), FulfillmentStatus.ASSIGNED, "system", null));
    return fulfillment;
  }

  private String envelope(UUID orderId, Map<String, Object> payload) {
    EventEnvelope envelope =
        new EventEnvelope(
            UUID.randomUUID(),
            "OrderCancellationRequested",
            1,
            Instant.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            orderId,
            "order-service",
            objectMapper.valueToTree(payload));
    return objectMapper.writeValueAsString(envelope);
  }
}
