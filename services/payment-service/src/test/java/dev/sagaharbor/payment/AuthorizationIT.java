package dev.sagaharbor.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.sagaharbor.payment.config.TestSecurityConfig;
import dev.sagaharbor.payment.config.TestcontainersConfiguration;
import dev.sagaharbor.payment.domain.PaymentAttempt;
import dev.sagaharbor.payment.domain.PaymentAttemptRepository;
import dev.sagaharbor.payment.domain.PaymentRepository;
import dev.sagaharbor.payment.messaging.EventEnvelope;
import dev.sagaharbor.payment.messaging.InventoryReservedListener;
import dev.sagaharbor.payment.messaging.OrderEventsListener;
import dev.sagaharbor.payment.messaging.OutboxEvent;
import dev.sagaharbor.payment.messaging.OutboxEventRepository;
import dev.sagaharbor.payment.service.OrderPaymentContextNotYetAvailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives OrderEventsListener and InventoryReservedListener directly against a full application
 * context backed by Testcontainers Postgres/Kafka, asserting directly against the database what the
 * authorization flow actually committed. Covers the approved, declined, duplicate-delivery, and
 * missing-order-context scenarios. None of these scenarios exercises the retry/circuit-breaker path
 * itself (ResilienceIT owns that), but the CircuitBreaker bean is still reset before each test here
 * too: Spring caches and reuses one ApplicationContext across every test class with this
 * exact @SpringBootTest/@Import configuration, so without a reset a prior ResilienceIT test that
 * left the circuit OPEN would otherwise leak into this class's "approved" test and make it flaky
 * depending on run order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class AuthorizationIT {

  private static final Path EVENTS_DIR = Path.of("../../contracts/events");

  @Autowired private OrderEventsListener orderEventsListener;
  @Autowired private InventoryReservedListener inventoryReservedListener;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private PaymentAttemptRepository paymentAttemptRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private CircuitBreaker paymentProviderCircuitBreaker;

  @BeforeEach
  void resetTheSharedCircuitBreaker() {
    paymentProviderCircuitBreaker.reset();
  }

  @Test
  void anOrderWithNoMatchingSimulatorRuleIsApprovedOnTheFirstAttempt() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    deliverOrderPlaced(orderId, correlationId, "50.00");
    deliverInventoryReserved(orderId, correlationId);

    assertThat(countRows("payments", "order_id = ? AND status = 'AUTHORIZED'", orderId))
        .isEqualTo(1);
    List<PaymentAttempt> attempts =
        paymentAttemptRepository.findByOrderIdOrderByAttemptNumber(orderId);
    assertThat(attempts).hasSize(1);
    assertThat(attempts.get(0).getOutcome().name()).isEqualTo("APPROVED");

    OutboxEvent authorizedEvent = outboxEventFor(orderId, "PaymentAuthorized");
    assertThat(schemaErrorsFor(authorizedEvent, "PaymentAuthorized")).isEmpty();
  }

  @Test
  void aSeededDeclineAmountIsDeclinedOnceAndNeverRetried() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    deliverOrderPlaced(orderId, correlationId, "1.00");
    deliverInventoryReserved(orderId, correlationId);

    assertThat(countRows("payments", "order_id = ? AND status = 'DECLINED'", orderId)).isEqualTo(1);
    List<PaymentAttempt> attempts =
        paymentAttemptRepository.findByOrderIdOrderByAttemptNumber(orderId);
    assertThat(attempts).hasSize(1);
    assertThat(attempts.get(0).getOutcome().name()).isEqualTo("DECLINED");

    OutboxEvent declinedEvent = outboxEventFor(orderId, "PaymentDeclined");
    assertThat(schemaErrorsFor(declinedEvent, "PaymentDeclined")).isEmpty();
  }

  @Test
  void aSecondDeliveryOfTheSameInventoryReservedEventIsANoOp() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    deliverOrderPlaced(orderId, correlationId, "50.00");
    UUID eventId = UUID.randomUUID();
    String envelopeJson = inventoryReservedEnvelopeJson(eventId, orderId, correlationId);

    inventoryReservedListener.onMessage(envelopeJson);
    inventoryReservedListener.onMessage(envelopeJson);

    assertThat(countRows("payments", "order_id = ?", orderId)).isEqualTo(1);
    assertThat(
            countRows(
                "outbox_event", "aggregate_id = ? AND event_type = 'PaymentAuthorized'", orderId))
        .isEqualTo(1);
  }

  @Test
  void inventoryReservedArrivingBeforeOrderPlacedThrowsARetryableException() {
    UUID orderId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();

    assertThatThrownBy(() -> deliverInventoryReserved(orderId, correlationId))
        .isInstanceOf(OrderPaymentContextNotYetAvailableException.class);

    assertThat(countRows("payments", "order_id = ?", orderId)).isEqualTo(0);
  }

  private void deliverOrderPlaced(UUID orderId, UUID correlationId, String totalAmount) {
    Map<String, Object> payload =
        Map.of(
            "customerId", UUID.randomUUID().toString(),
            "idempotencyKey", "test-" + UUID.randomUUID(),
            "items",
                List.of(
                    Map.of(
                        "sku",
                        "WIDGET-BLUE-M",
                        "quantity",
                        1,
                        "unitPrice",
                        Map.of("currencyCode", "USD", "amount", totalAmount))),
            "totalAmount", Map.of("currencyCode", "USD", "amount", totalAmount));
    EventEnvelope envelope =
        new EventEnvelope(
            UUID.randomUUID(),
            "OrderPlaced",
            1,
            Instant.now(),
            correlationId,
            null,
            orderId,
            "order-service",
            objectMapper.valueToTree(payload));
    orderEventsListener.onMessage(objectMapper.writeValueAsString(envelope));
  }

  private void deliverInventoryReserved(UUID orderId, UUID correlationId) {
    inventoryReservedListener.onMessage(
        inventoryReservedEnvelopeJson(UUID.randomUUID(), orderId, correlationId));
  }

  private String inventoryReservedEnvelopeJson(UUID eventId, UUID orderId, UUID correlationId) {
    Map<String, Object> payload =
        Map.of(
            "reservationId", UUID.randomUUID().toString(),
            "items", List.of(Map.of("sku", "WIDGET-BLUE-M", "quantity", 1)));
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            "InventoryReserved",
            1,
            Instant.now(),
            correlationId,
            UUID.randomUUID(),
            orderId,
            "inventory-service",
            objectMapper.valueToTree(payload));
    return objectMapper.writeValueAsString(envelope);
  }

  private OutboxEvent outboxEventFor(UUID orderId, String eventType) {
    return outboxEventRepository.findAll().stream()
        .filter(row -> row.getAggregateId().equals(orderId) && row.getEventType().equals(eventType))
        .findFirst()
        .orElseThrow();
  }

  private List<Error> schemaErrorsFor(OutboxEvent outboxRow, String schemaName) throws IOException {
    EventEnvelope envelope =
        new EventEnvelope(
            outboxRow.getEventId(),
            outboxRow.getEventType(),
            outboxRow.getEventVersion(),
            outboxRow.getOccurredAt(),
            outboxRow.getCorrelationId(),
            outboxRow.getCausationId(),
            outboxRow.getAggregateId(),
            outboxRow.getProducer(),
            objectMapper.readTree(outboxRow.getPayload()));
    String envelopeJson = objectMapper.writeValueAsString(envelope);
    return schemaFor(schemaName).validate(objectMapper.readTree(envelopeJson));
  }

  private int countRows(String table, String where, Object param) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, param);
    return count == null ? 0 : count;
  }

  private Schema schemaFor(String schemaName) throws IOException {
    Map<String, String> contentById = new HashMap<>();
    try (var files = Files.list(EVENTS_DIR)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".schema.json")).toList()) {
        String content = Files.readString(file);
        String id = objectMapper.readTree(content).get("$id").asString();
        contentById.put(id, content);
      }
    }
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemas(contentById));
    return registry.getSchema(
        SchemaLocation.of(
            "https://sagaharbor.dev/contracts/events/" + schemaName + ".v1.schema.json"));
  }
}
