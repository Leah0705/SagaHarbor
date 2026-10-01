package dev.sagaharbor.payment.web;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.sagaharbor.payment.config.SecurityConfig;
import dev.sagaharbor.payment.config.TestSecurityConfig;
import dev.sagaharbor.payment.messaging.DeadLetterEventRepository;
import dev.sagaharbor.payment.messaging.DeadLetterEventStatus;
import dev.sagaharbor.payment.messaging.OutboxEventRepository;
import java.util.List;
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

  private static JwtRequestPostProcessor role(String name) {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_" + name));
  }
}
