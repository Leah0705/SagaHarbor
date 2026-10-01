package dev.sagaharbor.order.service;

import dev.sagaharbor.order.domain.IncidentKind;
import dev.sagaharbor.order.domain.IncidentStatus;
import dev.sagaharbor.order.domain.OperationsIncidentRepository;
import dev.sagaharbor.order.domain.Order;
import dev.sagaharbor.order.domain.OrderRepository;
import dev.sagaharbor.order.domain.OrderReviewDecision;
import dev.sagaharbor.order.domain.OrderStatus;
import dev.sagaharbor.order.domain.OrderStatusHistory;
import dev.sagaharbor.order.domain.OrderStatusHistoryRepository;
import dev.sagaharbor.order.web.OrderNotFoundException;
import dev.sagaharbor.order.web.dto.OrderResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies an operator's decision on an order waiting in REQUIRES_REVIEW. An order gets there in two
 * ways, and each decision fits only some of them:
 *
 * <ul>
 *   <li>RESUME returns the order to its reviewResumeStatus: the status it held on entering review,
 *       moved forward by any milestone that arrived since. It applies to a review that started on
 *       the happy path, such as a cancellation requested after dispatch that the operator turns
 *       down. A review that started from a stuck cancellation cannot be resumed.
 *   <li>CANCEL starts compensation for whatever the order still holds, or retries a cancellation
 *       that ran out of retries. It is refused once the goods have left the warehouse, because a
 *       return flow is not part of this system.
 * </ul>
 *
 * Both decisions resolve the order's open review incidents in the same transaction, so the incident
 * queue and the order agree on what happened. A second decision on the same order fails with 409
 * because the order has left review; concurrent decisions are serialized by the order's version.
 */
@Service
public class OrderReviewService {

  private static final String RESUMED_REASON_CODE = "OPERATOR_RESUMED";
  private static final List<IncidentKind> REVIEW_INCIDENT_KINDS =
      List.of(
          IncidentKind.COMPENSATION_EXHAUSTED,
          IncidentKind.CANCELLATION_AFTER_DISPATCH,
          IncidentKind.CANCELLATION_STUCK);

  private final OrderRepository orderRepository;
  private final OrderStatusHistoryRepository statusHistoryRepository;
  private final OperationsIncidentRepository incidentRepository;
  private final OrderCancellationTransaction cancellationTransaction;
  private final IncidentActionService incidentActionService;
  private final OperationsProjectionUpdater projectionUpdater;

  public OrderReviewService(
      OrderRepository orderRepository,
      OrderStatusHistoryRepository statusHistoryRepository,
      OperationsIncidentRepository incidentRepository,
      OrderCancellationTransaction cancellationTransaction,
      IncidentActionService incidentActionService,
      OperationsProjectionUpdater projectionUpdater) {
    this.orderRepository = orderRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.incidentRepository = incidentRepository;
    this.cancellationTransaction = cancellationTransaction;
    this.incidentActionService = incidentActionService;
    this.projectionUpdater = projectionUpdater;
  }

  @Transactional
  public OrderResponse decide(
      UUID orderId,
      OrderReviewDecision decision,
      String operatorId,
      String note,
      UUID correlationId) {
    Order order =
        orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    if (order.getStatus() != OrderStatus.REQUIRES_REVIEW) {
      throw new ReviewDecisionNotAllowedException(
          "order " + orderId + " is not waiting for review (status: " + order.getStatus() + ")");
    }
    OrderStatus resumeStatus = order.getReviewResumeStatus();
    if (resumeStatus == null) {
      throw new ReviewDecisionNotAllowedException(
          "order " + orderId + " has no recorded status to decide from");
    }

    switch (decision) {
      case RESUME -> resume(order, resumeStatus);
      case CANCEL -> cancel(order, resumeStatus, operatorId, note, correlationId);
    }
    resolveReviewIncidents(orderId, operatorId, decision.name() + ": " + note);
    return OrderService.toResponse(orderRepository.findById(orderId).orElseThrow());
  }

  private void resume(Order order, OrderStatus resumeStatus) {
    if (!resumeStatus.isOnHappyPath()) {
      throw new ReviewDecisionNotAllowedException(
          "order "
              + order.getOrderId()
              + " came to review from a stuck cancellation; it can only be cancelled");
    }
    Instant now = Instant.now();
    order.updateStatus(resumeStatus);
    orderRepository.save(order);
    statusHistoryRepository.save(
        new OrderStatusHistory(order.getOrderId(), resumeStatus, RESUMED_REASON_CODE, now));
    projectionUpdater.advanceStage(order.getOrderId(), resumeStatus, RESUMED_REASON_CODE, now);
  }

  private void cancel(
      Order order, OrderStatus resumeStatus, String operatorId, String note, UUID correlationId) {
    if (resumeStatus == OrderStatus.DISPATCHED || resumeStatus == OrderStatus.DELIVERED) {
      throw new ReviewDecisionNotAllowedException(
          "order "
              + order.getOrderId()
              + " already left the warehouse ("
              + resumeStatus
              + "); it can only be resumed");
    }
    // A review that came from a stuck cancellation keeps that cancellation's own requirements; a
    // review that came from the happy path needs whatever the order had reached. Inventory is
    // always asked, for the same reason as a PENDING cancellation: see OrderCancellationService.
    boolean fromHappyPath = resumeStatus.isOnHappyPath();
    boolean paymentRefundRequired =
        fromHappyPath && !resumeStatus.isEarlierOnHappyPathThan(OrderStatus.PAYMENT_AUTHORIZED);
    boolean fulfillmentCancelRequired =
        fromHappyPath && !resumeStatus.isEarlierOnHappyPathThan(OrderStatus.FULFILLMENT_ASSIGNED);
    cancellationTransaction.startFromReview(
        order.getOrderId(),
        operatorId,
        note,
        true,
        paymentRefundRequired,
        fulfillmentCancelRequired,
        correlationId);
  }

  private void resolveReviewIncidents(UUID orderId, String operatorId, String resolutionNote) {
    for (IncidentKind kind : REVIEW_INCIDENT_KINDS) {
      incidentRepository
          .findByOrderIdAndKindAndStatusNot(orderId, kind, IncidentStatus.RESOLVED)
          .ifPresent(
              incident ->
                  incidentActionService.resolve(
                      incident.getIncidentId(), operatorId, resolutionNote));
    }
  }
}
