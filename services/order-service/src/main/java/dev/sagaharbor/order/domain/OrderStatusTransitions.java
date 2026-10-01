package dev.sagaharbor.order.domain;

import java.util.Map;
import java.util.Set;

/**
 * The allowed-transitions table from docs/ARCHITECTURE.md's order status state machine, made
 * explicit and checkable in code. Every cancellation of an order that has not left the warehouse
 * goes through CANCELLATION_PENDING, PENDING included: Order Service cannot know from its own
 * status whether Inventory already reserved stock, so it always asks and waits for the answer (see
 * OrderCancellationTransaction). CANCELLED is reachable directly only on an inventory rejection,
 * when Inventory has said it holds nothing. REQUIRES_REVIEW is entered when a cancellation arrives
 * after dispatch or a cancellation runs out of retries. Apart from an inventory rejection, an order
 * leaves review only through an operator decision (see OrderReviewService), which this table
 * deliberately does not cover, so no automated path can pull an order out of review.
 */
public final class OrderStatusTransitions {

  private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED =
      Map.ofEntries(
          Map.entry(
              OrderStatus.PENDING,
              Set.of(
                  OrderStatus.INVENTORY_RESERVED,
                  OrderStatus.CANCELLATION_PENDING,
                  OrderStatus.CANCELLED)),
          Map.entry(
              OrderStatus.INVENTORY_RESERVED,
              Set.of(OrderStatus.PAYMENT_AUTHORIZED, OrderStatus.CANCELLATION_PENDING)),
          Map.entry(
              OrderStatus.PAYMENT_AUTHORIZED,
              Set.of(OrderStatus.FULFILLMENT_ASSIGNED, OrderStatus.CANCELLATION_PENDING)),
          Map.entry(
              OrderStatus.FULFILLMENT_ASSIGNED,
              Set.of(OrderStatus.PICKING, OrderStatus.CANCELLATION_PENDING)),
          Map.entry(
              OrderStatus.PICKING, Set.of(OrderStatus.PACKED, OrderStatus.CANCELLATION_PENDING)),
          Map.entry(
              OrderStatus.PACKED, Set.of(OrderStatus.DISPATCHED, OrderStatus.CANCELLATION_PENDING)),
          Map.entry(
              OrderStatus.DISPATCHED, Set.of(OrderStatus.DELIVERED, OrderStatus.REQUIRES_REVIEW)),
          Map.entry(OrderStatus.DELIVERED, Set.of()),
          Map.entry(
              OrderStatus.CANCELLATION_PENDING,
              Set.of(OrderStatus.CANCELLED, OrderStatus.REQUIRES_REVIEW)),
          Map.entry(OrderStatus.CANCELLED, Set.of()),
          Map.entry(OrderStatus.REQUIRES_REVIEW, Set.of(OrderStatus.CANCELLED)));

  private OrderStatusTransitions() {}

  public static boolean isAllowed(OrderStatus from, OrderStatus to) {
    return ALLOWED.get(from).contains(to);
  }
}
