package dev.sagaharbor.order.web;

import dev.sagaharbor.order.service.OrderReviewService;
import dev.sagaharbor.order.web.dto.OrderResponse;
import dev.sagaharbor.order.web.dto.ReviewDecisionRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's way out of REQUIRES_REVIEW — see OrderReviewService for what RESUME and CANCEL do
 * and when each is refused. OPERATOR/ADMIN only, through the /api/v1/ops/** rule in SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/ops/orders")
public class OrderReviewController {

  private final OrderReviewService orderReviewService;

  public OrderReviewController(OrderReviewService orderReviewService) {
    this.orderReviewService = orderReviewService;
  }

  @Operation(summary = "Resume or cancel an order waiting in REQUIRES_REVIEW")
  @PostMapping("/{orderId}/review-decision")
  public OrderResponse decide(
      @PathVariable UUID orderId,
      @Valid @RequestBody ReviewDecisionRequest request,
      @AuthenticationPrincipal Jwt jwt,
      @RequestAttribute("correlationId") UUID correlationId) {
    return orderReviewService.decide(
        orderId, request.decision(), jwt.getSubject(), request.note(), correlationId);
  }
}
