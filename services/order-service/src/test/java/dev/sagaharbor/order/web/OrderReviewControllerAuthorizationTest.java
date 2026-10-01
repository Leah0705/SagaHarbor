package dev.sagaharbor.order.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.sagaharbor.order.config.SecurityConfig;
import dev.sagaharbor.order.config.TestSecurityConfig;
import dev.sagaharbor.order.domain.OrderReviewDecision;
import dev.sagaharbor.order.service.OrderReviewService;
import dev.sagaharbor.order.service.ReviewDecisionNotAllowedException;
import dev.sagaharbor.order.web.dto.MoneyDto;
import dev.sagaharbor.order.web.dto.OrderResponse;
import java.time.Instant;
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

@WebMvcTest(OrderReviewController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class, GlobalExceptionHandler.class})
class OrderReviewControllerAuthorizationTest {

  private static final String RESUME_BODY = "{\"decision\": \"RESUME\", \"note\": \"ship it\"}";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OrderReviewService orderReviewService;

  @Test
  void operatorCanDecideAReview() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderReviewService.decide(
            eq(orderId), eq(OrderReviewDecision.RESUME), any(), any(), any()))
        .thenReturn(sampleOrder(orderId));

    mockMvc
        .perform(
            post("/api/v1/ops/orders/{orderId}/review-decision", orderId)
                .with(jwtWithRole("OPERATOR"))
                .contentType("application/json")
                .content(RESUME_BODY))
        .andExpect(status().isOk());
    verify(orderReviewService)
        .decide(eq(orderId), eq(OrderReviewDecision.RESUME), any(), eq("ship it"), any());
  }

  @Test
  void customerCannotDecideAReview() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/ops/orders/{orderId}/review-decision", UUID.randomUUID())
                .with(jwtWithRole("CUSTOMER"))
                .contentType("application/json")
                .content(RESUME_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void aDecisionWithoutANoteIsRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/ops/orders/{orderId}/review-decision", UUID.randomUUID())
                .with(jwtWithRole("OPERATOR"))
                .contentType("application/json")
                .content("{\"decision\": \"CANCEL\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aDecisionTheOrderCannotTakeIsAConflict() throws Exception {
    when(orderReviewService.decide(any(), any(), any(), any(), any()))
        .thenThrow(new ReviewDecisionNotAllowedException("goods already left the warehouse"));

    mockMvc
        .perform(
            post("/api/v1/ops/orders/{orderId}/review-decision", UUID.randomUUID())
                .with(jwtWithRole("ADMIN"))
                .contentType("application/json")
                .content("{\"decision\": \"CANCEL\", \"note\": \"x\"}"))
        .andExpect(status().isConflict());
  }

  private static OrderResponse sampleOrder(UUID orderId) {
    return new OrderResponse(
        orderId,
        UUID.randomUUID(),
        "DISPATCHED",
        List.of(),
        new MoneyDto("USD", "10.00"),
        Instant.now(),
        null);
  }

  private static JwtRequestPostProcessor jwtWithRole(String role) {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }
}
