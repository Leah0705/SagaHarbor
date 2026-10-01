package dev.sagaharbor.order.service;

import dev.sagaharbor.order.domain.Order;
import dev.sagaharbor.order.domain.OrderRepository;
import dev.sagaharbor.order.domain.OrderRequiresReviewReasonCode;
import dev.sagaharbor.order.domain.OrderStatus;
import dev.sagaharbor.order.domain.OrderStatusHistory;
import dev.sagaharbor.order.domain.OrderStatusHistoryRepository;
import dev.sagaharbor.order.domain.OrderStatusTransitions;
import dev.sagaharbor.order.messaging.OutboxEventWriter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves an order to REQUIRES_REVIEW and publishes OrderRequiresReview.v1 — used for cancellation
 * requested at or after DISPATCHED (synchronous, from OrderCancellationService) and for a
 * compensation that reconciliation has given up waiting on (see ReconciliationService). The order
 * remembers the status it came from, which an operator RESUME returns it to (see
 * OrderReviewService). Incident creation is the caller's responsibility (via IncidentService) so
 * this stays focused on the order state change itself.
 */
@Component
public class OrderRequiresReviewTransaction {

  private static final String EVENT_TYPE = "OrderRequiresReview";
  private static final int EVENT_VERSION = 1;

  private final OrderRepository orderRepository;
  private final OrderStatusHistoryRepository statusHistoryRepository;
  private final OutboxEventWriter outboxEventWriter;
  private final OperationsProjectionUpdater projectionUpdater;

  public OrderRequiresReviewTransaction(
      OrderRepository orderRepository,
      OrderStatusHistoryRepository statusHistoryRepository,
      OutboxEventWriter outboxEventWriter,
      OperationsProjectionUpdater projectionUpdater) {
    this.orderRepository = orderRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.outboxEventWriter = outboxEventWriter;
    this.projectionUpdater = projectionUpdater;
  }

  @Transactional
  public void markRequiresReview(
      UUID orderId,
      OrderRequiresReviewReasonCode reasonCode,
      String reasonDetail,
      UUID correlationId,
      UUID causationId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    if (!OrderStatusTransitions.isAllowed(order.getStatus(), OrderStatus.REQUIRES_REVIEW)) {
      // Already resolved (CANCELLED/DELIVERED) or already REQUIRES_REVIEW — nothing to do.
      return;
    }

    Instant now = Instant.now();
    order.enterReview();
    orderRepository.save(order);
    statusHistoryRepository.save(
        new OrderStatusHistory(orderId, OrderStatus.REQUIRES_REVIEW, reasonCode.name(), now));
    projectionUpdater.advanceStage(orderId, OrderStatus.REQUIRES_REVIEW, reasonCode.name(), now);
    outboxEventWriter.write(
        EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        causationId,
        new Payload(reasonCode.name(), reasonDetail));
  }

  private record Payload(String reasonCode, String reasonDetail) {}
}
