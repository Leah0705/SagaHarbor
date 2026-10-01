package dev.sagaharbor.order.service;

/**
 * The order is not waiting for review, or the decision does not fit how it got there: a
 * cancellation cannot be resumed, and goods that already left the warehouse cannot be cancelled.
 * Mapped to 409.
 */
public class ReviewDecisionNotAllowedException extends RuntimeException {

  public ReviewDecisionNotAllowedException(String message) {
    super(message);
  }
}
