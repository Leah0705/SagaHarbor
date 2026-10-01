package dev.sagaharbor.order.web;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.sagaharbor.order.config.SecurityConfig;
import dev.sagaharbor.order.config.TestSecurityConfig;
import dev.sagaharbor.order.messaging.DeadLetterEvent;
import dev.sagaharbor.order.messaging.DeadLetterEventId;
import dev.sagaharbor.order.messaging.DeadLetterEventRepository;
import dev.sagaharbor.order.messaging.DeadLetterEventStatus;
import dev.sagaharbor.order.messaging.OutboxEvent;
import dev.sagaharbor.order.messaging.OutboxEventRepository;
import dev.sagaharbor.order.messaging.OutboxState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MessagingOpsController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class, GlobalExceptionHandler.class})
class MessagingOpsControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DeadLetterEventRepository deadLetters;
  @MockitoBean private OutboxEventRepository outbox;

  @Test
  void operatorCanReadMessagingStatusFromTheConsoleOrigin() throws Exception {
    when(deadLetters.findTop50ByStatusOrderByCreatedAtDesc(DeadLetterEventStatus.PENDING_REVIEW))
        .thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/ops/messaging")
                .header("Origin", "http://localhost:5173")
                .with(role("OPERATOR")))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(jsonPath("$.pendingDeadLetterCount").value(0))
        .andExpect(jsonPath("$.outboxBacklogCount").value(0));
  }

  @Test
  void adminCanReadButCustomerCannot() throws Exception {
    when(deadLetters.findTop50ByStatusOrderByCreatedAtDesc(DeadLetterEventStatus.PENDING_REVIEW))
        .thenReturn(List.of());
    mockMvc.perform(get("/api/v1/ops/messaging").with(role("ADMIN"))).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/ops/messaging").with(role("CUSTOMER")))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/v1/ops/messaging")).andExpect(status().isUnauthorized());
  }

  @Test
  void snapshotIncludesPendingDeadLettersAndUnpublishedOutbox() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    var event =
        new DeadLetterEvent(
            new DeadLetterEventId(eventId, "inventory-listener"),
            "sagaharbor.inventory.events",
            "InventoryReserved",
            orderId,
            "{}");
    var unpublished =
        new OutboxEvent(
            UUID.randomUUID(),
            "OrderPlaced",
            1,
            orderId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "order-service",
            "{}",
            Instant.parse("2026-01-01T00:00:00Z"),
            null);
    when(deadLetters.countByStatus(DeadLetterEventStatus.PENDING_REVIEW)).thenReturn(1L);
    when(deadLetters.findTop50ByStatusOrderByCreatedAtDesc(DeadLetterEventStatus.PENDING_REVIEW))
        .thenReturn(List.of(event));
    when(outbox.countByStateNot(OutboxState.PUBLISHED.name())).thenReturn(1L);
    when(outbox.findFirstByStateNotOrderByCreatedAtAsc(OutboxState.PUBLISHED.name()))
        .thenReturn(Optional.of(unpublished));

    mockMvc
        .perform(get("/api/v1/ops/messaging").with(role("OPERATOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendingDeadLetterCount").value(1))
        .andExpect(jsonPath("$.outboxBacklogCount").value(1))
        .andExpect(jsonPath("$.oldestOutboxEventAt").value("2026-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.deadLetters[0].eventId").value(eventId.toString()))
        .andExpect(jsonPath("$.deadLetters[0].aggregateId").value(orderId.toString()))
        .andExpect(jsonPath("$.deadLetters[0].envelopeJson").doesNotExist());
  }

  private static JwtRequestPostProcessor role(String name) {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_" + name));
  }
}
