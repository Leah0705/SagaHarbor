package dev.sagaharbor.order.service;

import dev.sagaharbor.order.domain.IncidentKind;
import dev.sagaharbor.order.domain.IncidentStatus;
import dev.sagaharbor.order.domain.OperationsIncidentRepository;
import dev.sagaharbor.order.domain.Order;
import dev.sagaharbor.order.domain.OrderCancellation;
import dev.sagaharbor.order.domain.OrderCancellationRepository;
import dev.sagaharbor.order.domain.OrderRepository;
import dev.sagaharbor.order.domain.OrderRequiresReviewReasonCode;
import dev.sagaharbor.order.domain.OrderStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Finds orders that have stopped making progress. Two different situations, handled differently:
 *
 * <ul>
 *   <li>A cancellation stuck past app.reconciliation.cancellation-stuck-threshold gets one safe
 *       retry, then escalates to REQUIRES_REVIEW, because compensation that cannot finish needs a
 *       human decision.
 *   <li>An order in a happy-path stage longer than that stage's threshold in
 *       app.ops.sla.stage-thresholds gets an SLA_BREACHED incident and keeps its status. The
 *       thresholds differ per stage because the stages differ: an automated step should finish in
 *       seconds, while a warehouse step is done by people and takes hours. Leaving the status alone
 *       means the order continues as soon as its next event arrives, and the incident tells an
 *       operator to look. app.reconciliation.stuck-threshold applies to a stage with no threshold
 *       of its own.
 * </ul>
 *
 * Guarded by a Postgres session-scoped advisory lock (see reconcile()) so running more than one
 * instance of this service never causes two replicas to act on the same stuck order at once — a
 * single, fixed lock key is enough since this whole method is one short, sequential unit of work,
 * not something that needs per-order locking. The lock is acquired and released on one JDBC
 * Connection held for the whole method, borrowed directly from the DataSource rather than through
 * JdbcTemplate — an advisory lock belongs to whichever database session acquired it, and
 * JdbcTemplate borrows a fresh pooled connection for every call outside of a transaction, so
 * acquiring and releasing through it could easily end up on two different sessions and leave the
 * lock stuck held. The rest of the method's database work (via the repositories and *Transaction
 * beans below) keeps using its own ordinary, independent transactions on other pooled connections,
 * exactly like every other orchestration method in this service — this connection is only ever
 * touched for the lock itself.
 */
@Service
public class ReconciliationService {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
  private static final long ADVISORY_LOCK_KEY = 8_772_364_981L;

  // Every non-terminal happy-path stage. CANCELLATION_PENDING has its own threshold and its own
  // recovery action (see reconcileStuckCancellations).
  private static final List<OrderStatus> WATCHED_STAGES =
      OrderStatus.HAPPY_PATH.stream().filter(stage -> stage != OrderStatus.DELIVERED).toList();

  private final DataSource dataSource;
  private final OrderRepository orderRepository;
  private final OrderCancellationRepository cancellationRepository;
  private final OperationsIncidentRepository incidentRepository;
  private final IncidentService incidentService;
  private final OrderCancellationTransaction cancellationTransaction;
  private final OrderRequiresReviewTransaction requiresReviewTransaction;
  private final OpsSlaProperties slaProperties;
  private final MeterRegistry meterRegistry;
  private final Duration stuckThreshold;
  private final Duration cancellationStuckThreshold;

  public ReconciliationService(
      DataSource dataSource,
      OrderRepository orderRepository,
      OrderCancellationRepository cancellationRepository,
      OperationsIncidentRepository incidentRepository,
      IncidentService incidentService,
      OrderCancellationTransaction cancellationTransaction,
      OrderRequiresReviewTransaction requiresReviewTransaction,
      OpsSlaProperties slaProperties,
      MeterRegistry meterRegistry,
      @Value("${app.reconciliation.stuck-threshold}") Duration stuckThreshold,
      @Value("${app.reconciliation.cancellation-stuck-threshold}")
          Duration cancellationStuckThreshold) {
    this.dataSource = dataSource;
    this.orderRepository = orderRepository;
    this.cancellationRepository = cancellationRepository;
    this.incidentRepository = incidentRepository;
    this.incidentService = incidentService;
    this.cancellationTransaction = cancellationTransaction;
    this.requiresReviewTransaction = requiresReviewTransaction;
    this.slaProperties = slaProperties;
    this.meterRegistry = meterRegistry;
    this.stuckThreshold = stuckThreshold;
    this.cancellationStuckThreshold = cancellationStuckThreshold;
  }

  public void reconcile() {
    Timer.Sample sample = Timer.start(meterRegistry);
    String outcome = "completed";
    try (Connection lockConnection = dataSource.getConnection()) {
      if (!tryAcquireLock(lockConnection)) {
        log.debug("another instance already holds the reconciliation lock, skipping this run");
        outcome = "skipped";
        return;
      }
      try {
        reconcileStuckCancellations();
        reportStagesPastTheirThreshold();
      } finally {
        releaseLock(lockConnection);
      }
    } catch (SQLException e) {
      outcome = "failed";
      throw new IllegalStateException("failed to acquire the reconciliation advisory lock", e);
    } catch (RuntimeException e) {
      outcome = "failed";
      throw e;
    } finally {
      sample.stop(meterRegistry.timer("reconciliation.run", "outcome", outcome));
    }
  }

  private void reconcileStuckCancellations() {
    Instant cutoff = Instant.now().minus(cancellationStuckThreshold);
    for (OrderCancellation tracker :
        cancellationRepository.findByResolvedAtIsNullAndRequestedAtBefore(cutoff)) {
      // An order already escalated to REQUIRES_REVIEW keeps an unresolved tracker until an
      // operator decides; it is not stuck again on every pass.
      boolean stillPending =
          orderRepository
              .findById(tracker.getOrderId())
              .map(order -> order.getStatus() == OrderStatus.CANCELLATION_PENDING)
              .orElse(false);
      if (stillPending) {
        handleStuckCancellation(tracker);
      }
    }
  }

  private void handleStuckCancellation(OrderCancellation tracker) {
    UUID orderId = tracker.getOrderId();
    // An ACKNOWLEDGED incident still means the one retry was spent. Only RESOLVED resets it.
    boolean alreadyRetried =
        incidentRepository
            .findByOrderIdAndKindAndStatusNot(
                orderId, IncidentKind.CANCELLATION_STUCK, IncidentStatus.RESOLVED)
            .isPresent();
    meterRegistry
        .counter("reconciliation.stuck.orders", "stage", "CANCELLATION_PENDING")
        .increment();

    if (!alreadyRetried) {
      log.info("cancellation stuck for order {}, attempting one safe recovery retry", orderId);
      incidentService.openOrDeduplicate(
          orderId,
          IncidentKind.CANCELLATION_STUCK,
          "cancellation pending since "
              + tracker.getRequestedAt()
              + ", waiting on: "
              + outstanding(tracker));
      cancellationTransaction.republishCancellationRequested(
          orderId, tracker.getReasonDetail(), UUID.randomUUID());
      meterRegistry.counter("reconciliation.recovery.outcome", "outcome", "retried").increment();
      return;
    }

    log.warn("cancellation still stuck for order {} after a recovery retry, escalating", orderId);
    requiresReviewTransaction.markRequiresReview(
        orderId,
        OrderRequiresReviewReasonCode.COMPENSATION_EXHAUSTED,
        "cancellation compensation did not complete after a retried request: "
            + outstanding(tracker),
        UUID.randomUUID(),
        null);
    incidentService.openOrDeduplicate(
        orderId,
        IncidentKind.COMPENSATION_EXHAUSTED,
        "cancellation compensation exhausted: " + outstanding(tracker));
    meterRegistry.counter("reconciliation.recovery.outcome", "outcome", "escalated").increment();
  }

  private void reportStagesPastTheirThreshold() {
    Instant now = Instant.now();
    for (OrderStatus stage : WATCHED_STAGES) {
      Duration threshold =
          Optional.ofNullable(slaProperties.thresholdFor(stage)).orElse(stuckThreshold);
      for (Order order :
          orderRepository.findByStatusAndUpdatedAtBefore(stage, now.minus(threshold))) {
        reportStageBreach(order, threshold);
      }
    }
  }

  // One SLA_BREACHED incident per stage visit: skipped while an earlier one is still unresolved,
  // and skipped once one was raised after the order entered its current stage, even if an operator
  // has resolved it since.
  private void reportStageBreach(Order order, Duration threshold) {
    UUID orderId = order.getOrderId();
    boolean alreadyReported =
        incidentRepository.existsByOrderIdAndKindAndStatusNot(
                orderId, IncidentKind.SLA_BREACHED, IncidentStatus.RESOLVED)
            || incidentRepository.existsByOrderIdAndKindAndCreatedAtAfter(
                orderId, IncidentKind.SLA_BREACHED, order.getUpdatedAt());
    if (alreadyReported) {
      return;
    }
    log.warn(
        "order {} has been in {} since {}, past its {} threshold",
        orderId,
        order.getStatus(),
        order.getUpdatedAt(),
        threshold);
    incidentService.openOrDeduplicate(
        orderId,
        IncidentKind.SLA_BREACHED,
        "in "
            + order.getStatus()
            + " since "
            + order.getUpdatedAt()
            + ", past its "
            + threshold
            + " threshold");
    meterRegistry
        .counter("reconciliation.stuck.orders", "stage", order.getStatus().name())
        .increment();
  }

  private static String outstanding(OrderCancellation tracker) {
    StringBuilder outstanding = new StringBuilder();
    if (tracker.isInventoryReleaseRequired()) {
      outstanding.append("inventory release, ");
    }
    if (tracker.isPaymentRefundRequired()) {
      outstanding.append("payment refund, ");
    }
    if (tracker.isFulfillmentCancelRequired()) {
      outstanding.append("fulfillment cancellation, ");
    }
    return outstanding.isEmpty() ? "nothing (already fully confirmed)" : outstanding.toString();
  }

  private boolean tryAcquireLock(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
      statement.setLong(1, ADVISORY_LOCK_KEY);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    }
  }

  private void releaseLock(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
      statement.setLong(1, ADVISORY_LOCK_KEY);
      statement.execute();
    }
  }
}
