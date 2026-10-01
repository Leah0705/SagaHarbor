package dev.sagaharbor.inventory.service;

import dev.sagaharbor.inventory.cache.InventoryAvailabilityCache;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates release attempts, same bounded-retry shape as ReservationService, for the
 * PaymentDeclined.v1, fulfillment-cancellation, and OrderCancellationRequested.v1 listeners.
 */
@Service
public class ReservationReleaseService {

  private static final Logger log = LoggerFactory.getLogger(ReservationReleaseService.class);

  private final ReservationReleaseTransaction releaseTransaction;
  private final InventoryAvailabilityCache availabilityCache;
  private final int maxAttempts;

  public ReservationReleaseService(
      ReservationReleaseTransaction releaseTransaction,
      InventoryAvailabilityCache availabilityCache,
      @Value("${app.inventory.reservation.max-attempts}") int maxAttempts) {
    this.releaseTransaction = releaseTransaction;
    this.availabilityCache = availabilityCache;
    this.maxAttempts = maxAttempts;
  }

  public ReleaseOutcome release(
      UUID orderId, ReleaseReasonCode reasonCode, UUID correlationId, UUID causationId) {
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        ReleaseOutcome outcome =
            releaseTransaction.attempt(orderId, reasonCode, correlationId, causationId);
        if (outcome instanceof ReleaseOutcome.Released released) {
          released.items().forEach(item -> availabilityCache.evict(item.sku()));
        }
        return outcome;
      } catch (OptimisticLockingFailureException conflict) {
        log.info(
            "optimistic lock conflict releasing order {}, attempt {} of {}",
            orderId,
            attempt,
            maxAttempts);
        if (attempt == maxAttempts) {
          throw new StockConcurrencyException("order " + orderId, maxAttempts, conflict);
        }
        RetryBackoff.pauseBeforeRetry(attempt);
      }
    }
    throw new IllegalStateException("unreachable: loop above always returns or throws");
  }

  /**
   * Answers OrderCancellationRequested.v1 for an order that has a reservation. An active
   * reservation is released. One released earlier gets its InventoryReleased.v1 published again,
   * because Order Service asks again only while it is still waiting, which means the first answer
   * never reached it.
   */
  public void releaseForCancellationRequest(UUID orderId, UUID correlationId, UUID causationId) {
    ReleaseOutcome outcome =
        release(orderId, ReleaseReasonCode.ORDER_CANCELLED, correlationId, causationId);
    if (outcome instanceof ReleaseOutcome.AlreadyReleased) {
      releaseTransaction.republishReleased(
          orderId, ReleaseReasonCode.ORDER_CANCELLED, correlationId, causationId);
    }
  }
}
