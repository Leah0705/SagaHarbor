package dev.sagaharbor.order.service;

import dev.sagaharbor.order.domain.Order;
import dev.sagaharbor.order.domain.OrderCancellationRepository;
import dev.sagaharbor.order.domain.OrderRepository;
import dev.sagaharbor.order.domain.OrderStatus;
import dev.sagaharbor.order.domain.OrderStatusHistory;
import dev.sagaharbor.order.domain.OrderStatusHistoryRepository;
import dev.sagaharbor.order.domain.OrderStatusTransitions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the "everything is going fine" forward milestones (inventory reserved, payment
 * authorized, fulfillment assigned, and each fulfillment status step) to an order's status and
 * history. Three states change what a milestone means:
 *
 * <ul>
 *   <li>The order's cancellation is still open (CANCELLATION_PENDING, or REQUIRES_REVIEW after a
 *       cancellation ran out of retries): Order Service has just learned, later than expected, that
 *       a compensation is needed, so the milestone folds into the cancellation tracker — see
 *       OrderCancellationTransaction's fold* methods.
 *   <li>The order waits in REQUIRES_REVIEW: the status stays put for the operator's decision, and
 *       the milestone only moves the status a later RESUME returns the order to.
 *   <li>The order is already CANCELLED: a downstream service started work after the cancellation,
 *       so the cancellation request is re-published — see
 *       OrderCancellationTransaction.republishForLateMilestone.
 * </ul>
 *
 * FulfillmentStatusChanged with newStatus=CANCELLED is deliberately not handled here — that always
 * goes through OrderCancellationTransaction directly, since it starts or confirms a cancellation
 * rather than advancing one.
 */
@Component
public class OrderLifecycleTransaction {

  private final OrderRepository orderRepository;
  private final OrderStatusHistoryRepository statusHistoryRepository;
  private final OrderCancellationRepository cancellationRepository;
  private final OrderCancellationTransaction cancellationTransaction;
  private final OperationsProjectionUpdater projectionUpdater;
  private final MeterRegistry meterRegistry;

  public OrderLifecycleTransaction(
      OrderRepository orderRepository,
      OrderStatusHistoryRepository statusHistoryRepository,
      OrderCancellationRepository cancellationRepository,
      OrderCancellationTransaction cancellationTransaction,
      OperationsProjectionUpdater projectionUpdater,
      MeterRegistry meterRegistry) {
    this.orderRepository = orderRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.cancellationRepository = cancellationRepository;
    this.cancellationTransaction = cancellationTransaction;
    this.projectionUpdater = projectionUpdater;
    this.meterRegistry = meterRegistry;
  }

  @Transactional
  public void onInventoryReserved(UUID orderId, UUID correlationId, UUID causationId) {
    onWorkStarted(orderId, OrderStatus.INVENTORY_RESERVED, correlationId, causationId);
  }

  @Transactional
  public void onPaymentAuthorized(UUID orderId, UUID correlationId, UUID causationId) {
    onWorkStarted(orderId, OrderStatus.PAYMENT_AUTHORIZED, correlationId, causationId);
  }

  @Transactional
  public void onFulfillmentAssigned(UUID orderId, UUID correlationId, UUID causationId) {
    onWorkStarted(orderId, OrderStatus.FULFILLMENT_ASSIGNED, correlationId, causationId);
  }

  /** newStatus is one of PICKING, PACKED, DISPATCHED, DELIVERED — never CANCELLED. */
  @Transactional
  public void onFulfillmentStatusChanged(UUID orderId, OrderStatus newStatus) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    applyForward(order, newStatus);
  }

  // The three milestones that mean a downstream service now holds something for this order
  // (reserved stock, an authorized payment, a warehouse job), which a cancellation must undo.
  private void onWorkStarted(
      UUID orderId, OrderStatus milestone, UUID correlationId, UUID causationId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    if (hasOpenCancellation(order)) {
      switch (milestone) {
        case INVENTORY_RESERVED -> cancellationTransaction.foldInventoryReleaseRequirement(orderId);
        case PAYMENT_AUTHORIZED -> cancellationTransaction.foldPaymentRefundRequirement(orderId);
        case FULFILLMENT_ASSIGNED ->
            cancellationTransaction.foldFulfillmentCancelRequirement(orderId);
        default -> throw new IllegalArgumentException("not a work-started milestone: " + milestone);
      }
      return;
    }
    if (order.getStatus() == OrderStatus.CANCELLED) {
      cancellationTransaction.republishForLateMilestone(
          orderId, milestone, correlationId, causationId);
      return;
    }
    applyForward(order, milestone);
  }

  private boolean hasOpenCancellation(Order order) {
    return switch (order.getStatus()) {
      case CANCELLATION_PENDING -> true;
      case REQUIRES_REVIEW -> cancellationRepository.existsById(order.getOrderId());
      default -> false;
    };
  }

  private void applyForward(Order order, OrderStatus target) {
    if (order.getStatus() == target) {
      return; // already applied — a harmless duplicate on top of the inbox's own dedup
    }
    if (order.getStatus() == OrderStatus.REQUIRES_REVIEW) {
      order.observeMilestoneDuringReview(target);
      orderRepository.save(order);
      return;
    }
    if (!OrderStatusTransitions.isAllowed(order.getStatus(), target)) {
      if (order.getStatus().isEarlierOnHappyPathThan(target)) {
        throw new OrderMilestoneTooEarlyException(order.getOrderId(), order.getStatus());
      }
      // The order already moved past this point, or is being cancelled — this milestone is stale
      // and safe to ignore.
      return;
    }

    OrderStatus previousStatus = order.getStatus();
    Instant enteredPreviousStatusAt = order.getUpdatedAt();
    Instant now = Instant.now();
    order.updateStatus(target);
    orderRepository.save(order);
    statusHistoryRepository.save(new OrderStatusHistory(order.getOrderId(), target, null, now));
    projectionUpdater.advanceStage(order.getOrderId(), target, null, now);

    meterRegistry
        .timer("order.stage.duration", "fromStage", previousStatus.name(), "toStage", target.name())
        .record(Duration.between(enteredPreviousStatusAt, now));
  }
}
