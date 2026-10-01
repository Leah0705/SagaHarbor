package dev.sagaharbor.fulfillment.service;

import dev.sagaharbor.fulfillment.domain.OrderCancellationMarker;
import dev.sagaharbor.fulfillment.domain.OrderCancellationMarkerRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps this service from starting work on an order that Order Service already cancelled. Two
 * listeners touch the same order from different Kafka topics, on different consumer threads:
 * creating a fulfillment (PaymentAuthorized.v1), and handling OrderCancellationRequested.v1.
 * Without coordination the check "was this order cancelled?" could run just before the cancellation
 * is recorded, and the work would start anyway with nothing left to undo it.
 *
 * <p>Both paths therefore take a transaction-scoped Postgres advisory lock keyed by the order id
 * before they read or write the order's cancellation marker. The lock is held until the caller's
 * transaction ends, so whichever path comes second sees everything the first one committed: either
 * the marker, and the work is skipped, or the finished work, which the cancellation then
 * compensates. Callers run inside their listener's transaction; called outside one, the check still
 * works but no longer serializes against the other path.
 */
@Component
public class OrderCancellationGuard {

  private final OrderCancellationMarkerRepository markerRepository;

  public OrderCancellationGuard(OrderCancellationMarkerRepository markerRepository) {
    this.markerRepository = markerRepository;
  }

  /** Locks the order and reports whether its cancellation request was already processed. */
  @Transactional
  public boolean lockAndCheckCancelled(UUID orderId) {
    markerRepository.acquireTransactionLock(lockKey(orderId));
    return markerRepository.existsById(orderId);
  }

  /** Locks the order and records its cancellation request; a repeated request changes nothing. */
  @Transactional
  public void lockAndMarkCancelled(UUID orderId, UUID correlationId) {
    markerRepository.acquireTransactionLock(lockKey(orderId));
    if (!markerRepository.existsById(orderId)) {
      markerRepository.save(new OrderCancellationMarker(orderId, correlationId));
    }
  }

  // Folds the 128-bit order id into the 64-bit advisory lock key space. Two orders that collide
  // only wait for each other briefly; correctness never depends on keys being distinct.
  static long lockKey(UUID orderId) {
    return orderId.getMostSignificantBits() ^ orderId.getLeastSignificantBits();
  }
}
