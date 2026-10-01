package dev.sagaharbor.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sagaharbor.order.domain.IncidentKind;
import dev.sagaharbor.order.domain.IncidentStatus;
import dev.sagaharbor.order.domain.OperationsIncident;
import dev.sagaharbor.order.domain.OperationsIncidentRepository;
import dev.sagaharbor.order.domain.Order;
import dev.sagaharbor.order.domain.OrderRepository;
import dev.sagaharbor.order.domain.OrderReviewDecision;
import dev.sagaharbor.order.domain.OrderStatus;
import dev.sagaharbor.order.domain.OrderStatusHistoryRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderReviewServiceTest {

  private final OrderRepository orderRepository = mock(OrderRepository.class);
  private final OrderStatusHistoryRepository statusHistoryRepository =
      mock(OrderStatusHistoryRepository.class);
  private final OperationsIncidentRepository incidentRepository =
      mock(OperationsIncidentRepository.class);
  private final OrderCancellationTransaction cancellationTransaction =
      mock(OrderCancellationTransaction.class);
  private final IncidentActionService incidentActionService = mock(IncidentActionService.class);
  private final OperationsProjectionUpdater projectionUpdater =
      mock(OperationsProjectionUpdater.class);

  private final OrderReviewService service =
      new OrderReviewService(
          orderRepository,
          statusHistoryRepository,
          incidentRepository,
          cancellationTransaction,
          incidentActionService,
          projectionUpdater);

  @Test
  void resumeReturnsTheOrderToTheStatusItCameFrom() {
    Order order = orderInReviewFrom(OrderStatus.DISPATCHED);

    service.decide(
        order.getOrderId(), OrderReviewDecision.RESUME, "operator-1", "ship it", UUID.randomUUID());

    assertThat(order.getStatus()).isEqualTo(OrderStatus.DISPATCHED);
    assertThat(order.getReviewResumeStatus()).isNull();
    verify(projectionUpdater)
        .advanceStage(eq(order.getOrderId()), eq(OrderStatus.DISPATCHED), any(), any());
  }

  @Test
  void resumeContinuesFromAMilestoneObservedDuringTheReview() {
    Order order = orderInReviewFrom(OrderStatus.DISPATCHED);
    order.observeMilestoneDuringReview(OrderStatus.DELIVERED);

    service.decide(order.getOrderId(), OrderReviewDecision.RESUME, "operator-1", "delivered", null);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
  }

  @Test
  void aReviewThatCameFromAStuckCancellationCannotBeResumed() {
    Order order = orderInReviewFrom(OrderStatus.CANCELLATION_PENDING);

    assertThatThrownBy(
            () ->
                service.decide(
                    order.getOrderId(), OrderReviewDecision.RESUME, "operator-1", "x", null))
        .isInstanceOf(ReviewDecisionNotAllowedException.class);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.REQUIRES_REVIEW);
  }

  @Test
  void cancelIsRefusedOnceTheGoodsLeftTheWarehouse() {
    Order order = orderInReviewFrom(OrderStatus.DISPATCHED);

    assertThatThrownBy(
            () ->
                service.decide(
                    order.getOrderId(), OrderReviewDecision.CANCEL, "operator-1", "x", null))
        .isInstanceOf(ReviewDecisionNotAllowedException.class);
    verify(cancellationTransaction, never())
        .startFromReview(any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any());
  }

  @Test
  void cancelAfterAssignmentCompensatesInventoryPaymentAndFulfillment() {
    Order order = orderInReviewFrom(OrderStatus.PICKING);
    UUID correlationId = UUID.randomUUID();

    service.decide(
        order.getOrderId(), OrderReviewDecision.CANCEL, "operator-1", "fraud", correlationId);

    verify(cancellationTransaction)
        .startFromReview(
            order.getOrderId(), "operator-1", "fraud", true, true, true, correlationId);
  }

  @Test
  void cancelBeforePaymentOnlyAsksInventory() {
    Order order = orderInReviewFrom(OrderStatus.INVENTORY_RESERVED);

    service.decide(order.getOrderId(), OrderReviewDecision.CANCEL, "operator-1", "x", null);

    verify(cancellationTransaction)
        .startFromReview(order.getOrderId(), "operator-1", "x", true, false, false, null);
  }

  @Test
  void retryingAStuckCancellationKeepsTheTrackersOwnRequirements() {
    Order order = orderInReviewFrom(OrderStatus.CANCELLATION_PENDING);

    service.decide(order.getOrderId(), OrderReviewDecision.CANCEL, "operator-1", "retry", null);

    verify(cancellationTransaction)
        .startFromReview(order.getOrderId(), "operator-1", "retry", true, false, false, null);
  }

  @Test
  void aDecisionResolvesTheOrdersOpenReviewIncidents() {
    Order order = orderInReviewFrom(OrderStatus.DISPATCHED);
    OperationsIncident incident =
        new OperationsIncident(order.getOrderId(), IncidentKind.CANCELLATION_AFTER_DISPATCH, "x");
    when(incidentRepository.findByOrderIdAndKindAndStatusNot(
            order.getOrderId(), IncidentKind.CANCELLATION_AFTER_DISPATCH, IncidentStatus.RESOLVED))
        .thenReturn(Optional.of(incident));

    service.decide(
        order.getOrderId(), OrderReviewDecision.RESUME, "operator-1", "deliver it", null);

    verify(incidentActionService)
        .resolve(incident.getIncidentId(), "operator-1", "RESUME: deliver it");
  }

  @Test
  void anOrderThatIsNotInReviewIsRefused() {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "USD", new BigDecimal("10.00"));
    order.updateStatus(OrderStatus.PICKING);
    when(orderRepository.findById(order.getOrderId())).thenReturn(Optional.of(order));

    assertThatThrownBy(
            () ->
                service.decide(
                    order.getOrderId(), OrderReviewDecision.RESUME, "operator-1", "x", null))
        .isInstanceOf(ReviewDecisionNotAllowedException.class);
  }

  private Order orderInReviewFrom(OrderStatus status) {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "USD", new BigDecimal("10.00"));
    order.updateStatus(status);
    order.enterReview();
    when(orderRepository.findById(order.getOrderId())).thenReturn(Optional.of(order));
    return order;
  }
}
