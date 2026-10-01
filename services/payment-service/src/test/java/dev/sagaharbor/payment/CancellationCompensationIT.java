package dev.sagaharbor.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sagaharbor.payment.config.TestSecurityConfig;
import dev.sagaharbor.payment.config.TestcontainersConfiguration;
import dev.sagaharbor.payment.domain.Payment;
import dev.sagaharbor.payment.domain.PaymentRepository;
import dev.sagaharbor.payment.messaging.EventEnvelope;
import dev.sagaharbor.payment.messaging.FulfillmentCancelledListener;
import dev.sagaharbor.payment.messaging.InventoryReservedListener;
import dev.sagaharbor.payment.messaging.OrderEventsListener;
import java.math.BigDecimal;
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
 * Covers the autonomous compensation listeners: FulfillmentCancelledListener and
 * OrderEventsListener's OrderCancellationRequested handling. Both converge on
 * RefundService.refundForCompensation, which is a safe no-op when there's no authorized payment to
 * refund. A cancellation request also leaves a marker that stops a later InventoryReserved from
 * authorizing, and a repeated request after a refund publishes that refund again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class CancellationCompensationIT {

  @Autowired private OrderEventsListener orderEventsListener;
  @Autowired private InventoryReservedListener inventoryReservedListener;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FulfillmentCancelledListener fulfillmentCancelledListener;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void fulfillmentCancelledRefundsAnAuthorizedPayment() {
    Payment payment = saveAuthorizedPayment(new BigDecimal("25.00"));

    fulfillmentCancelledListener.onMessage(
        envelope(
            "FulfillmentStatusChanged",
            payment.getOrderId(),
            Map.of(
                "fulfillmentId",
                UUID.randomUUID().toString(),
                "previousStatus",
                "PICKING",
                "newStatus",
                "CANCELLED")));

    assertThat(paymentRepository.findById(payment.getPaymentId()).orElseThrow().getStatus().name())
        .isEqualTo("REFUNDED");
  }

  @Test
  void fulfillmentCancelledWithNoPaymentIsANoOp() {
    fulfillmentCancelledListener.onMessage(
        envelope(
            "FulfillmentStatusChanged",
            UUID.randomUUID(),
            Map.of(
                "fulfillmentId",
                UUID.randomUUID().toString(),
                "previousStatus",
                "PICKING",
                "newStatus",
                "CANCELLED")));
    // No exception — there is no payment for this order, nothing to refund.
  }

  @Test
  void orderCancellationRequestedRefundsAnAuthorizedPayment() {
    Payment payment = saveAuthorizedPayment(new BigDecimal("40.00"));

    orderEventsListener.onMessage(
        envelope(
            "OrderCancellationRequested",
            payment.getOrderId(),
            Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "changed their mind")));

    assertThat(paymentRepository.findById(payment.getPaymentId()).orElseThrow().getStatus().name())
        .isEqualTo("REFUNDED");
  }

  @Test
  void orderCancellationRequestedBeforeAuthorizationIsANoOp() {
    orderEventsListener.onMessage(
        envelope(
            "OrderCancellationRequested",
            UUID.randomUUID(),
            Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "too early")));
    // No exception — no OrderPlacedListener context and no payment exist for this order.
  }

  @Test
  void repeatedFulfillmentCancellationNeverRefundsTwice() {
    Payment payment = saveAuthorizedPayment(new BigDecimal("15.00"));
    String cancelledJson =
        envelope(
            "FulfillmentStatusChanged",
            payment.getOrderId(),
            Map.of(
                "fulfillmentId",
                UUID.randomUUID().toString(),
                "previousStatus",
                "PICKING",
                "newStatus",
                "CANCELLED"));

    fulfillmentCancelledListener.onMessage(cancelledJson);
    fulfillmentCancelledListener.onMessage(cancelledJson);

    assertThat(paymentRepository.findById(payment.getPaymentId()).orElseThrow().getStatus().name())
        .isEqualTo("REFUNDED");
  }

  @Test
  void anInventoryReservationAfterTheCancellationRequestNeverAuthorizes() {
    UUID orderId = UUID.randomUUID();
    orderEventsListener.onMessage(
        envelope(
            "OrderPlaced",
            orderId,
            Map.of(
                "customerId",
                UUID.randomUUID().toString(),
                "totalAmount",
                Map.of("currencyCode", "USD", "amount", "30.00"))));
    orderEventsListener.onMessage(cancellationRequested(orderId));

    inventoryReservedListener.onMessage(
        envelope(
            "InventoryReserved",
            orderId,
            Map.of(
                "reservationId",
                UUID.randomUUID().toString(),
                "items",
                List.of(Map.of("sku", "WIDGET-BLUE-M", "quantity", 1)))));

    assertThat(paymentRepository.findByOrderId(orderId)).isEmpty();
    assertThat(outboxEventsFor(orderId, "PaymentAuthorized")).isZero();
  }

  @Test
  void aRepeatedCancellationRequestPublishesTheEarlierRefundAgain() {
    Payment payment = saveAuthorizedPayment(new BigDecimal("20.00"));
    orderEventsListener.onMessage(cancellationRequested(payment.getOrderId()));
    assertThat(outboxEventsFor(payment.getOrderId(), "PaymentRefunded")).isEqualTo(1);

    // Order Service asks again because the first PaymentRefunded never reached it.
    orderEventsListener.onMessage(cancellationRequested(payment.getOrderId()));

    assertThat(outboxEventsFor(payment.getOrderId(), "PaymentRefunded")).isEqualTo(2);
    assertThat(paymentRepository.findById(payment.getPaymentId()).orElseThrow().getStatus().name())
        .isEqualTo("REFUNDED");
  }

  private String cancellationRequested(UUID orderId) {
    return envelope(
        "OrderCancellationRequested",
        orderId,
        Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "changed their mind"));
  }

  private long outboxEventsFor(UUID orderId, String eventType) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
        Long.class,
        orderId,
        eventType);
  }

  private Payment saveAuthorizedPayment(BigDecimal amount) {
    Payment payment =
        Payment.authorized(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            amount,
            "USD",
            UUID.randomUUID());
    return paymentRepository.save(payment);
  }

  private String envelope(String eventType, UUID orderId, Map<String, Object> payload) {
    EventEnvelope envelope =
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
