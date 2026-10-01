package dev.sagaharbor.order.domain;

import java.util.List;

/** Matches the orders.status and order_status_history.status CHECK constraints. */
public enum OrderStatus {
  PENDING,
  INVENTORY_RESERVED,
  PAYMENT_AUTHORIZED,
  FULFILLMENT_ASSIGNED,
  PICKING,
  PACKED,
  DISPATCHED,
  DELIVERED,
  CANCELLATION_PENDING,
  CANCELLED,
  REQUIRES_REVIEW;

  /**
   * The forward path an order takes when nothing goes wrong. Used to tell a milestone that arrived
   * early from one that is stale, and to decide which status an operator RESUME returns an order
   * to.
   */
  public static final List<OrderStatus> HAPPY_PATH =
      List.of(
          PENDING,
          INVENTORY_RESERVED,
          PAYMENT_AUTHORIZED,
          FULFILLMENT_ASSIGNED,
          PICKING,
          PACKED,
          DISPATCHED,
          DELIVERED);

  public boolean isOnHappyPath() {
    return HAPPY_PATH.contains(this);
  }

  /** True when both statuses are on the happy path and this one comes before other. */
  public boolean isEarlierOnHappyPathThan(OrderStatus other) {
    int thisRank = HAPPY_PATH.indexOf(this);
    int otherRank = HAPPY_PATH.indexOf(other);
    return thisRank >= 0 && otherRank >= 0 && thisRank < otherRank;
  }

  /**
   * Every non-terminal status with normal-flow SLA meaning — used by the ops backlog/work-queue/
   * stuck-orders KPIs (StageDurationKpiService, WorkQueueService). REQUIRES_REVIEW is deliberately
   * excluded: an order lands there because something needed a human decision, not because of
   * normal-flow stage duration, and it's already surfaced through the incident queue instead.
   */
  public static final List<OrderStatus> OPEN_STAGES =
      List.of(
          PENDING,
          INVENTORY_RESERVED,
          PAYMENT_AUTHORIZED,
          FULFILLMENT_ASSIGNED,
          PICKING,
          PACKED,
          DISPATCHED,
          CANCELLATION_PENDING);
}
