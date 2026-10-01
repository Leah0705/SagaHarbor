package dev.sagaharbor.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderReviewStateTest {

  @Test
  void enteringReviewRemembersTheStatusTheOrderCameFrom() {
    Order order = orderIn(OrderStatus.DISPATCHED);

    order.enterReview();

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REQUIRES_REVIEW);
    assertThat(order.getReviewResumeStatus()).isEqualTo(OrderStatus.DISPATCHED);
  }

  @Test
  void aLaterMilestoneDuringReviewMovesTheResumeStatusForward() {
    Order order = orderIn(OrderStatus.DISPATCHED);
    order.enterReview();

    order.observeMilestoneDuringReview(OrderStatus.DELIVERED);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REQUIRES_REVIEW);
    assertThat(order.getReviewResumeStatus()).isEqualTo(OrderStatus.DELIVERED);
  }

  @Test
  void aStaleMilestoneDuringReviewIsIgnored() {
    Order order = orderIn(OrderStatus.PACKED);
    order.enterReview();

    order.observeMilestoneDuringReview(OrderStatus.PICKING);

    assertThat(order.getReviewResumeStatus()).isEqualTo(OrderStatus.PACKED);
  }

  @Test
  void aReviewThatCameFromACancellationIgnoresHappyPathMilestones() {
    Order order = orderIn(OrderStatus.CANCELLATION_PENDING);
    order.enterReview();

    order.observeMilestoneDuringReview(OrderStatus.PAYMENT_AUTHORIZED);

    assertThat(order.getReviewResumeStatus()).isEqualTo(OrderStatus.CANCELLATION_PENDING);
  }

  @Test
  void leavingReviewClearsTheResumeStatus() {
    Order order = orderIn(OrderStatus.DISPATCHED);
    order.enterReview();

    order.updateStatus(OrderStatus.DISPATCHED);

    assertThat(order.getReviewResumeStatus()).isNull();
  }

  private static Order orderIn(OrderStatus status) {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "USD", new BigDecimal("10.00"));
    order.updateStatus(status);
    return order;
  }
}
