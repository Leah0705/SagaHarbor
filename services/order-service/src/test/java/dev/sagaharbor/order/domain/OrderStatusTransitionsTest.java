package dev.sagaharbor.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OrderStatusTransitionsTest {

  @Test
  void allowsTheHappyPathForward() {
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.PENDING, OrderStatus.INVENTORY_RESERVED))
        .isTrue();
    assertThat(
            OrderStatusTransitions.isAllowed(
                OrderStatus.INVENTORY_RESERVED, OrderStatus.PAYMENT_AUTHORIZED))
        .isTrue();
    assertThat(
            OrderStatusTransitions.isAllowed(
                OrderStatus.PAYMENT_AUTHORIZED, OrderStatus.FULFILLMENT_ASSIGNED))
        .isTrue();
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.DISPATCHED, OrderStatus.DELIVERED))
        .isTrue();
  }

  @Test
  void rejectsSkippingAheadInTheWorkflow() {
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.PENDING, OrderStatus.DELIVERED))
        .isFalse();
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.PENDING, OrderStatus.PAYMENT_AUTHORIZED))
        .isFalse();
  }

  @Test
  void rejectsMovingBackward() {
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.PAYMENT_AUTHORIZED, OrderStatus.PENDING))
        .isFalse();
  }

  @Test
  void terminalStatusesAllowNoFurtherTransitions() {
    for (OrderStatus target : OrderStatus.values()) {
      assertThat(OrderStatusTransitions.isAllowed(OrderStatus.DELIVERED, target)).isFalse();
      assertThat(OrderStatusTransitions.isAllowed(OrderStatus.CANCELLED, target)).isFalse();
    }
  }

  @Test
  void cancellationBeforeDispatchGoesThroughCancellationPending() {
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.PACKED, OrderStatus.CANCELLATION_PENDING))
        .isTrue();
    assertThat(
            OrderStatusTransitions.isAllowed(
                OrderStatus.CANCELLATION_PENDING, OrderStatus.CANCELLED))
        .isTrue();
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.PACKED, OrderStatus.CANCELLED))
        .isFalse();
  }

  @Test
  void cancellationIsNeverDirectlyReachableAtOrAfterDispatch() {
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.DISPATCHED, OrderStatus.CANCELLED))
        .isFalse();
    assertThat(
            OrderStatusTransitions.isAllowed(
                OrderStatus.DISPATCHED, OrderStatus.CANCELLATION_PENDING))
        .isFalse();
  }

  @Test
  void cancellationRequestedAtOrAfterDispatchEscalatesStraightToRequiresReview() {
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.DISPATCHED, OrderStatus.REQUIRES_REVIEW))
        .isTrue();
  }

  @Test
  void pendingCancellationWaitsForInventoryLikeEveryOtherCancellation() {
    // OrderPlaced.v1 may already be on its way to Inventory, so a PENDING cancellation also waits
    // for Inventory's answer instead of finishing on the spot.
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.PENDING, OrderStatus.CANCELLATION_PENDING))
        .isTrue();
  }

  @Test
  void onlyAnInventoryRejectionCancelsAPendingOrderDirectly() {
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.PENDING, OrderStatus.CANCELLED))
        .isTrue();
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.INVENTORY_RESERVED, OrderStatus.CANCELLED))
        .isFalse();
  }

  @Test
  void reconciliationNeverMovesAHappyPathOrderIntoReview() {
    // A stage past its SLA threshold only raises an incident (see ReconciliationService), so no
    // happy-path stage before dispatch has a transition into REQUIRES_REVIEW at all.
    for (OrderStatus stage :
        List.of(
            OrderStatus.PENDING,
            OrderStatus.INVENTORY_RESERVED,
            OrderStatus.PAYMENT_AUTHORIZED,
            OrderStatus.FULFILLMENT_ASSIGNED,
            OrderStatus.PICKING,
            OrderStatus.PACKED)) {
      assertThat(OrderStatusTransitions.isAllowed(stage, OrderStatus.REQUIRES_REVIEW))
          .as(stage.name())
          .isFalse();
    }
  }

  @Test
  void aStuckCancellationCanEscalateToRequiresReview() {
    assertThat(
            OrderStatusTransitions.isAllowed(
                OrderStatus.CANCELLATION_PENDING, OrderStatus.REQUIRES_REVIEW))
        .isTrue();
  }

  @Test
  void onlyAnOperatorDecisionMovesAnOrderOutOfReviewToProcessing() {
    // RESUME and CANCEL go through OrderReviewService, outside this table, so no automated path
    // can apply them. An inventory rejection can still cancel outright.
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.REQUIRES_REVIEW, OrderStatus.CANCELLED))
        .isTrue();
    for (OrderStatus target : OrderStatus.values()) {
      if (target != OrderStatus.CANCELLED) {
        assertThat(OrderStatusTransitions.isAllowed(OrderStatus.REQUIRES_REVIEW, target))
            .as(target.name())
            .isFalse();
      }
    }
  }
}
