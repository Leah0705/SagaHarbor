package dev.sagaharbor.fulfillment.service;

import dev.sagaharbor.fulfillment.domain.FulfillmentRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates fulfillment creation for an authorized payment. The orderId lookup below is
 * defense-in-depth on top of PaymentAuthorizedListener's own inbox dedup (fulfillments.order_id is
 * also UNIQUE at the database level) — it's what keeps "create exactly one fulfillment per paid
 * order" true even if this method were ever called twice for the same order. An order whose
 * cancellation request already arrived gets no fulfillment at all — see OrderCancellationGuard.
 */
@Service
public class FulfillmentAssignmentService {

  private static final Logger log = LoggerFactory.getLogger(FulfillmentAssignmentService.class);

  private final FulfillmentRepository fulfillmentRepository;
  private final FulfillmentAssignmentTransaction assignmentTransaction;
  private final OrderCancellationGuard cancellationGuard;

  public FulfillmentAssignmentService(
      FulfillmentRepository fulfillmentRepository,
      FulfillmentAssignmentTransaction assignmentTransaction,
      OrderCancellationGuard cancellationGuard) {
    this.fulfillmentRepository = fulfillmentRepository;
    this.assignmentTransaction = assignmentTransaction;
    this.cancellationGuard = cancellationGuard;
  }

  public void assign(UUID orderId, UUID correlationId, UUID causationId) {
    // Checked first, under a lock held until the listener's transaction ends, so a cancellation
    // request for this order cannot slip in between this check and creating the fulfillment.
    if (cancellationGuard.lockAndCheckCancelled(orderId)) {
      log.info("order {} was cancelled before its fulfillment was created, skipping", orderId);
      return;
    }
    if (fulfillmentRepository.findByOrderId(orderId).isPresent()) {
      log.info("fulfillment already exists for order {}, skipping", orderId);
      return;
    }
    assignmentTransaction.assign(orderId, correlationId, causationId);
  }
}
