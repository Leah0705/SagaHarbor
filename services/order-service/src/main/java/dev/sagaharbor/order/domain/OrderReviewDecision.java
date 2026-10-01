package dev.sagaharbor.order.domain;

/**
 * An operator's decision on an order waiting in REQUIRES_REVIEW (see OrderReviewService). RESUME
 * returns the order to normal processing; CANCEL starts, or retries, compensation for whatever the
 * order still holds.
 */
public enum OrderReviewDecision {
  RESUME,
  CANCEL
}
