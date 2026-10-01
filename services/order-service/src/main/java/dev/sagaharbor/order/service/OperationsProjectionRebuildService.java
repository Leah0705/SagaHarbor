package dev.sagaharbor.order.service;

import dev.sagaharbor.order.domain.IncidentStatus;
import dev.sagaharbor.order.domain.OperationsIncidentRepository;
import dev.sagaharbor.order.domain.Order;
import dev.sagaharbor.order.domain.OrderOperationsProjection;
import dev.sagaharbor.order.domain.OrderOperationsProjectionRepository;
import dev.sagaharbor.order.domain.OrderRepository;
import dev.sagaharbor.order.domain.OrderStageDuration;
import dev.sagaharbor.order.domain.OrderStageDurationRepository;
import dev.sagaharbor.order.domain.OrderStatus;
import dev.sagaharbor.order.domain.OrderStatusHistory;
import dev.sagaharbor.order.domain.OrderStatusHistoryRepository;
import dev.sagaharbor.order.domain.ProjectionRebuildRun;
import dev.sagaharbor.order.domain.ProjectionRebuildRunRepository;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes order_operations_projection/order_stage_duration entirely from this service's own
 * durable tables — orders (including the three event-payload-only facts persisted there
 * specifically to make this possible, see V4__operations.sql) and order_status_history. Kafka
 * retention here is finite and there's no replay tooling in this codebase, but every lifecycle
 * event this service has ever processed already left a durable row before this service ever
 * advanced past it — replaying those rows is a complete, deterministic reconstruction, not an
 * approximation. low_stock_signal is deliberately not touched: it's a SKU-scoped latest-state
 * cache, not order-scoped history, and naturally catches up as new InventoryLowStock.v1 events
 * arrive.
 */
@Service
public class OperationsProjectionRebuildService {

  private final OrderRepository orderRepository;
  private final OrderStatusHistoryRepository statusHistoryRepository;
  private final OperationsIncidentRepository incidentRepository;
  private final OrderOperationsProjectionRepository projectionRepository;
  private final OrderStageDurationRepository stageDurationRepository;
  private final ProjectionRebuildRunRepository rebuildRunRepository;

  public OperationsProjectionRebuildService(
      OrderRepository orderRepository,
      OrderStatusHistoryRepository statusHistoryRepository,
      OperationsIncidentRepository incidentRepository,
      OrderOperationsProjectionRepository projectionRepository,
      OrderStageDurationRepository stageDurationRepository,
      ProjectionRebuildRunRepository rebuildRunRepository) {
    this.orderRepository = orderRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.incidentRepository = incidentRepository;
    this.projectionRepository = projectionRepository;
    this.stageDurationRepository = stageDurationRepository;
    this.rebuildRunRepository = rebuildRunRepository;
  }

  @Transactional
  public ProjectionRebuildRun rebuild(String triggeredBy) {
    ProjectionRebuildRun run = rebuildRunRepository.save(new ProjectionRebuildRun(triggeredBy));

    stageDurationRepository.deleteAllInBatch();
    projectionRepository.deleteAllInBatch();

    int ordersProcessed = 0;
    for (Order order : orderRepository.findAll()) {
      rebuildOneOrder(order);
      ordersProcessed++;
    }

    run.complete(ordersProcessed);
    return rebuildRunRepository.save(run);
  }

  private void rebuildOneOrder(Order order) {
    List<OrderStatusHistory> history =
        statusHistoryRepository.findByOrderIdOrderByOccurredAt(order.getOrderId());
    if (history.isEmpty()) {
      return; // can't happen for any order created through OrderCreationTransaction
    }

    OrderOperationsProjection projection =
        new OrderOperationsProjection(
            order.getOrderId(),
            order.getCustomerId(),
            order.getStatus(),
            order.getCurrencyCode(),
            order.getTotalAmount(),
            history.get(0).getOccurredAt());
    projection.recordInventoryRejection(order.getInventoryRejectionReasonCode());
    projection.recordPaymentOutcome(
        order.getPaymentDeclineReasonCode(), order.getPaymentTechnicalFailureCount());

    // Same shape as the live path in OperationsProjectionUpdater: a stage the order re-entered
    // reuses its existing row, reopened, rather than getting a second one.
    Map<OrderStatus, OrderStageDuration> stageDurations = new EnumMap<>(OrderStatus.class);
    for (int i = 0; i < history.size(); i++) {
      OrderStatusHistory entry = history.get(i);
      Instant enteredAt = entry.getOccurredAt();
      Instant exitedAt = i + 1 < history.size() ? history.get(i + 1).getOccurredAt() : null;

      OrderStageDuration stageDuration = stageDurations.get(entry.getStatus());
      if (stageDuration == null) {
        stageDuration = new OrderStageDuration(order.getOrderId(), entry.getStatus(), enteredAt);
        stageDurations.put(entry.getStatus(), stageDuration);
      } else {
        stageDuration.reopen();
      }
      if (exitedAt != null) {
        stageDuration.close(exitedAt);
      }

      // advanceStage's reasonCode meaning depends on the status it's paired with — see
      // OrderOperationsProjection.advanceStage's Javadoc. Replaying every history row (not just
      // the last one) reconstructs cancellationReasonCode/requiresReviewReasonCode exactly the
      // way the live path set them, even if the order later moved past that stage.
      projection.advanceStage(entry.getStatus(), entry.getReasonCode(), enteredAt);
    }

    stageDurationRepository.saveAll(stageDurations.values());

    int openIncidentCount =
        incidentRepository.countByOrderIdAndStatusNot(order.getOrderId(), IncidentStatus.RESOLVED);
    projection.setOpenIncidentCount(openIncidentCount);

    projectionRepository.save(projection);
  }
}
