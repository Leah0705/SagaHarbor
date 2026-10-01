package dev.sagaharbor.order.domain;

/**
 * COMPENSATION_EXHAUSTED and CANCELLATION_AFTER_DISPATCH mirror OrderRequiresReview.v1's reasonCode
 * enum in contracts/events/ — an incident of either kind is always paired with that event.
 * CANCELLATION_STUCK is reconciliation-only: an order left in CANCELLATION_PENDING past the
 * configured threshold. It escalates to a COMPENSATION_EXHAUSTED incident (and an actual
 * OrderRequiresReview.v1) only after one safe recovery attempt has already been made and the order
 * is still stuck — see ReconciliationService. SLA_BREACHED is reconciliation-only too: an order
 * stayed in a happy-path stage longer than that stage's threshold in app.ops.sla.stage-thresholds.
 * It never changes the order's status, so the order keeps moving the moment its next event arrives.
 */
public enum IncidentKind {
  COMPENSATION_EXHAUSTED,
  CANCELLATION_AFTER_DISPATCH,
  CANCELLATION_STUCK,
  SLA_BREACHED
}
