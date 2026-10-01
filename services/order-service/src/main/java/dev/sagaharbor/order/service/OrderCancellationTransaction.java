package dev.sagaharbor.order.service;

import dev.sagaharbor.order.domain.Order;
import dev.sagaharbor.order.domain.OrderCancellation;
import dev.sagaharbor.order.domain.OrderCancellationReasonCode;
import dev.sagaharbor.order.domain.OrderCancellationRepository;
import dev.sagaharbor.order.domain.OrderCancelledReasonCode;
import dev.sagaharbor.order.domain.OrderRepository;
import dev.sagaharbor.order.domain.OrderStatus;
import dev.sagaharbor.order.domain.OrderStatusHistory;
import dev.sagaharbor.order.domain.OrderStatusHistoryRepository;
import dev.sagaharbor.order.domain.OrderStatusTransitions;
import dev.sagaharbor.order.messaging.OutboxEventWriter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place every cancellation path in this service goes through, whatever triggered it: a
 * customer/operator HTTP request, an operator's review decision, PaymentDeclined, or a fulfillment
 * cancellation (either a direct operator action on Fulfillment Service or Fulfillment reacting to
 * our own OrderCancellationRequested.v1). Only an inventory rejection cancels immediately, since
 * Inventory has then said it holds nothing — see onInventoryRejected. Every other cancellation
 * waits in CANCELLATION_PENDING for whichever of {inventory release, payment refund, fulfillment
 * cancellation} it needs, tracked by one OrderCancellation row — see startOrMerge and the confirm*
 * methods. A PENDING order is no exception: its OrderPlaced.v1 may already be on Kafka, so
 * Inventory may reserve stock after this service has decided to cancel, and the order must wait for
 * Inventory's answer.
 */
@Component
public class OrderCancellationTransaction {

  private static final Logger log = LoggerFactory.getLogger(OrderCancellationTransaction.class);
  private static final String CANCELLED_EVENT_TYPE = "OrderCancelled";
  private static final String CANCELLATION_REQUESTED_EVENT_TYPE = "OrderCancellationRequested";
  private static final int EVENT_VERSION = 1;

  private final OrderRepository orderRepository;
  private final OrderStatusHistoryRepository statusHistoryRepository;
  private final OrderCancellationRepository cancellationRepository;
  private final OutboxEventWriter outboxEventWriter;
  private final OperationsProjectionUpdater projectionUpdater;

  public OrderCancellationTransaction(
      OrderRepository orderRepository,
      OrderStatusHistoryRepository statusHistoryRepository,
      OrderCancellationRepository cancellationRepository,
      OutboxEventWriter outboxEventWriter,
      OperationsProjectionUpdater projectionUpdater) {
    this.orderRepository = orderRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.cancellationRepository = cancellationRepository;
    this.outboxEventWriter = outboxEventWriter;
    this.projectionUpdater = projectionUpdater;
  }

  /**
   * InventoryRejected.v1 means Inventory holds nothing for this order. If a cancellation is already
   * tracked, that is Inventory's answer to it: there is nothing to release, so the inventory side
   * is confirmed. Otherwise the order was still waiting on Inventory and cancels outright.
   */
  @Transactional
  public void onInventoryRejected(
      UUID orderId, String reasonDetail, UUID correlationId, UUID causationId) {
    if (cancellationRepository.existsById(orderId)) {
      confirmInventoryRelease(orderId, correlationId, causationId);
      return;
    }
    finalizeDirectly(
        orderId,
        OrderCancelledReasonCode.INVENTORY_REJECTED,
        reasonDetail,
        correlationId,
        causationId);
  }

  private void finalizeDirectly(
      UUID orderId,
      OrderCancelledReasonCode reasonCode,
      String reasonDetail,
      UUID correlationId,
      UUID causationId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    if (order.getStatus() == OrderStatus.CANCELLED) {
      return;
    }
    if (!OrderStatusTransitions.isAllowed(order.getStatus(), OrderStatus.CANCELLED)) {
      throw new OrderMilestoneTooEarlyException(orderId, order.getStatus());
    }

    Instant now = Instant.now();
    order.updateStatus(OrderStatus.CANCELLED);
    orderRepository.save(order);
    statusHistoryRepository.save(
        new OrderStatusHistory(orderId, OrderStatus.CANCELLED, reasonCode.name(), now));
    projectionUpdater.advanceStage(orderId, OrderStatus.CANCELLED, reasonCode.name(), now);
    outboxEventWriter.write(
        CANCELLED_EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        causationId,
        new CancelledPayload(reasonCode.name(), reasonDetail));
  }

  /**
   * Starts a new compensation-tracked cancellation, or — if one is already in flight for this order
   * — merges in any newly-discovered requirement instead of creating a second row. Required flags
   * only ever go false -> true here, never the reverse, so merging is always safe.
   */
  @Transactional
  public void startOrMerge(
      UUID orderId,
      String requestedBy,
      String reasonDetail,
      OrderCancellationReasonCode reasonCode,
      boolean inventoryReleaseRequired,
      boolean paymentRefundRequired,
      boolean fulfillmentCancelRequired,
      UUID correlationId,
      UUID causationId,
      boolean emitCancellationRequestedEvent) {
    Optional<OrderCancellation> existing = cancellationRepository.findById(orderId);
    if (existing.isPresent()) {
      mergeRequirements(
          existing.get(),
          inventoryReleaseRequired,
          paymentRefundRequired,
          fulfillmentCancelRequired);
      return;
    }

    Order order = orderRepository.findById(orderId).orElseThrow();
    if (!OrderStatusTransitions.isAllowed(order.getStatus(), OrderStatus.CANCELLATION_PENDING)) {
      // Already CANCELLED/DELIVERED/REQUIRES_REVIEW, or (unexpectedly) DISPATCHED — the order is
      // already resolved one way or another, or waits for an operator, so nothing starts here.
      return;
    }

    Instant now = Instant.now();
    order.updateStatus(OrderStatus.CANCELLATION_PENDING);
    orderRepository.save(order);
    statusHistoryRepository.save(
        new OrderStatusHistory(orderId, OrderStatus.CANCELLATION_PENDING, reasonCode.name(), now));
    projectionUpdater.advanceStage(
        orderId, OrderStatus.CANCELLATION_PENDING, reasonCode.name(), now);
    cancellationRepository.save(
        new OrderCancellation(
            orderId,
            requestedBy,
            reasonDetail,
            reasonCode,
            inventoryReleaseRequired,
            paymentRefundRequired,
            fulfillmentCancelRequired));

    if (emitCancellationRequestedEvent) {
      outboxEventWriter.write(
          CANCELLATION_REQUESTED_EVENT_TYPE,
          EVENT_VERSION,
          orderId,
          correlationId,
          causationId,
          new CancellationRequestedPayload(reasonCode.name(), reasonDetail));
    }
  }

  /**
   * ReconciliationService's one safe recovery action for a cancellation stuck past its threshold: a
   * verbatim re-publish of OrderCancellationRequested.v1. Safe because every consumer of that event
   * checks its own local state before acting — a repeat is a no-op wherever the compensation
   * already happened, and a useful nudge wherever it didn't (e.g. the original attempt itself
   * dead-lettered).
   */
  @Transactional
  public void republishCancellationRequested(
      UUID orderId, String reasonDetail, UUID correlationId) {
    outboxEventWriter.write(
        CANCELLATION_REQUESTED_EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        null,
        new CancellationRequestedPayload("RECONCILIATION_RETRY", reasonDetail));
  }

  /**
   * An operator's CANCEL decision on an order in REQUIRES_REVIEW (see OrderReviewService). When the
   * review came from a cancellation that ran out of retries, its tracker is reused: requirements
   * and confirmations recorded so far stay, and the clock restarts so reconciliation gives it a new
   * retry window. Otherwise a new operator-requested cancellation starts. Either way the request
   * goes out again, and every downstream service answers it even if its own compensation already
   * happened, so a confirmation that was lost before the review can still arrive.
   */
  @Transactional
  public void startFromReview(
      UUID orderId,
      String operatorId,
      String note,
      boolean inventoryReleaseRequired,
      boolean paymentRefundRequired,
      boolean fulfillmentCancelRequired,
      UUID correlationId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    Optional<OrderCancellation> existing = cancellationRepository.findById(orderId);
    OrderCancellation tracker;
    if (existing.isPresent()) {
      tracker = existing.get();
      tracker.restartClock();
      mergeRequirements(
          tracker, inventoryReleaseRequired, paymentRefundRequired, fulfillmentCancelRequired);
    } else {
      tracker =
          cancellationRepository.save(
              new OrderCancellation(
                  orderId,
                  operatorId,
                  note,
                  OrderCancellationReasonCode.OPERATOR_REQUESTED,
                  inventoryReleaseRequired,
                  paymentRefundRequired,
                  fulfillmentCancelRequired));
    }

    String reasonCode = OrderCancellationReasonCode.OPERATOR_REQUESTED.name();
    Instant now = Instant.now();
    order.updateStatus(OrderStatus.CANCELLATION_PENDING);
    orderRepository.save(order);
    statusHistoryRepository.save(
        new OrderStatusHistory(orderId, OrderStatus.CANCELLATION_PENDING, reasonCode, now));
    projectionUpdater.advanceStage(orderId, OrderStatus.CANCELLATION_PENDING, reasonCode, now);
    outboxEventWriter.write(
        CANCELLATION_REQUESTED_EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        null,
        new CancellationRequestedPayload(reasonCode, note));

    // Every confirmation may already be in: they are recorded on the tracker even while the order
    // waits in review.
    tryFinalize(tracker, correlationId, null);
  }

  /**
   * A milestone meaning a downstream service started work (stock reserved, payment authorized,
   * fulfillment assigned) arrived after this order was already CANCELLED. Payment and Fulfillment
   * refuse new work once they have seen the cancellation request, so this normally means the work
   * started before they saw it, and they compensate it themselves when they do. Re-publishing the
   * request covers the case where a service never processed it, for example because it
   * dead-lettered there. Only cancellations that went out as OrderCancellationRequested.v1 are
   * re-published: the other triggers (a payment decline, a fulfillment cancellation) compensate
   * through their own events.
   */
  @Transactional
  public void republishForLateMilestone(
      UUID orderId, OrderStatus milestone, UUID correlationId, UUID causationId) {
    Optional<OrderCancellation> tracker = cancellationRepository.findById(orderId);
    if (tracker.isEmpty() || !wasRequestedOverKafka(tracker.get())) {
      return;
    }
    log.info(
        "{} arrived for cancelled order {}, re-publishing its cancellation request",
        milestone,
        orderId);
    outboxEventWriter.write(
        CANCELLATION_REQUESTED_EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        causationId,
        new CancellationRequestedPayload(
            tracker.get().getCancellationReasonCode().name(), tracker.get().getReasonDetail()));
  }

  private static boolean wasRequestedOverKafka(OrderCancellation tracker) {
    OrderCancellationReasonCode reasonCode = tracker.getCancellationReasonCode();
    return reasonCode == OrderCancellationReasonCode.CUSTOMER_REQUESTED
        || reasonCode == OrderCancellationReasonCode.OPERATOR_REQUESTED;
  }

  @Transactional
  public void confirmInventoryRelease(UUID orderId, UUID correlationId, UUID causationId) {
    OrderCancellation tracker = requireTracker(orderId);
    if (tracker.isResolved()) {
      return;
    }
    tracker.confirmInventoryRelease();
    cancellationRepository.save(tracker);
    tryFinalize(tracker, correlationId, causationId);
  }

  @Transactional
  public void confirmPaymentRefund(UUID orderId, UUID correlationId, UUID causationId) {
    OrderCancellation tracker = requireTracker(orderId);
    if (tracker.isResolved()) {
      return;
    }
    tracker.confirmPaymentRefund();
    cancellationRepository.save(tracker);
    tryFinalize(tracker, correlationId, causationId);
  }

  @Transactional
  public void confirmFulfillmentCancel(UUID orderId, UUID correlationId, UUID causationId) {
    OrderCancellation tracker = requireTracker(orderId);
    if (tracker.isResolved()) {
      return;
    }
    tracker.confirmFulfillmentCancel();
    cancellationRepository.save(tracker);
    tryFinalize(tracker, correlationId, causationId);
  }

  /**
   * Called when a milestone event (InventoryReserved/PaymentAuthorized/FulfillmentAssigned) arrives
   * for an order whose cancellation is still open — an out-of-order delivery, since these travel on
   * a different topic than whatever started the cancellation. It means Order Service now knows this
   * compensation genuinely is required, even though it wasn't known to be at the moment
   * cancellation started.
   */
  @Transactional
  public void foldInventoryReleaseRequirement(UUID orderId) {
    OrderCancellation tracker = requireTracker(orderId);
    tracker.requireInventoryRelease();
    cancellationRepository.save(tracker);
  }

  @Transactional
  public void foldPaymentRefundRequirement(UUID orderId) {
    OrderCancellation tracker = requireTracker(orderId);
    tracker.requirePaymentRefund();
    cancellationRepository.save(tracker);
  }

  @Transactional
  public void foldFulfillmentCancelRequirement(UUID orderId) {
    OrderCancellation tracker = requireTracker(orderId);
    tracker.requireFulfillmentCancel();
    cancellationRepository.save(tracker);
  }

  private void mergeRequirements(
      OrderCancellation tracker,
      boolean inventoryReleaseRequired,
      boolean paymentRefundRequired,
      boolean fulfillmentCancelRequired) {
    if (inventoryReleaseRequired) {
      tracker.requireInventoryRelease();
    }
    if (paymentRefundRequired) {
      tracker.requirePaymentRefund();
    }
    if (fulfillmentCancelRequired) {
      tracker.requireFulfillmentCancel();
    }
    cancellationRepository.save(tracker);
  }

  private void tryFinalize(OrderCancellation tracker, UUID correlationId, UUID causationId) {
    if (!tracker.isFullyConfirmed()) {
      return;
    }
    UUID orderId = tracker.getOrderId();
    Order order = orderRepository.findById(orderId).orElseThrow();
    if (order.getStatus() != OrderStatus.CANCELLATION_PENDING) {
      return;
    }

    Instant now = Instant.now();
    order.updateStatus(OrderStatus.CANCELLED);
    orderRepository.save(order);
    statusHistoryRepository.save(
        new OrderStatusHistory(
            orderId, OrderStatus.CANCELLED, tracker.getCancellationReasonCode().name(), now));
    projectionUpdater.advanceStage(
        orderId, OrderStatus.CANCELLED, tracker.getCancellationReasonCode().name(), now);
    tracker.markResolved();
    cancellationRepository.save(tracker);

    outboxEventWriter.write(
        CANCELLED_EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        causationId,
        new CancelledPayload(
            tracker.getCancellationReasonCode().name(), tracker.getReasonDetail()));
  }

  private OrderCancellation requireTracker(UUID orderId) {
    return cancellationRepository
        .findById(orderId)
        .orElseThrow(() -> new OrderCancellationNotYetTrackedException(orderId));
  }

  private record CancelledPayload(String reasonCode, String reasonDetail) {}

  private record CancellationRequestedPayload(String reasonCode, String reasonDetail) {}
}
