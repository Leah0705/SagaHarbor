package dev.sagaharbor.order.service;

import dev.sagaharbor.order.domain.IncidentActionHistory;
import dev.sagaharbor.order.domain.IncidentActionHistoryRepository;
import dev.sagaharbor.order.domain.IncidentActionType;
import dev.sagaharbor.order.domain.IncidentKind;
import dev.sagaharbor.order.domain.IncidentStatus;
import dev.sagaharbor.order.domain.OperationsIncident;
import dev.sagaharbor.order.domain.OperationsIncidentRepository;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Creates an operations incident, or returns the already-unresolved one of the same kind for the
 * same order — a database partial unique index (see V3__saga.sql/V4__operations.sql) is what makes
 * this safe even under a concurrent double-detection, not just the check below. "Unresolved" (not
 * just "OPEN") because an ACKNOWLEDGED-but-unresolved incident must still block a duplicate.
 */
@Service
public class IncidentService {

  private static final String SYSTEM_ACTOR = "system";

  private final OperationsIncidentRepository repository;
  private final IncidentActionHistoryRepository actionHistoryRepository;
  private final OperationsProjectionUpdater projectionUpdater;

  public IncidentService(
      OperationsIncidentRepository repository,
      IncidentActionHistoryRepository actionHistoryRepository,
      OperationsProjectionUpdater projectionUpdater) {
    this.repository = repository;
    this.actionHistoryRepository = actionHistoryRepository;
    this.projectionUpdater = projectionUpdater;
  }

  public OperationsIncident openOrDeduplicate(UUID orderId, IncidentKind kind, String detail) {
    return repository
        .findByOrderIdAndKindAndStatusNot(orderId, kind, IncidentStatus.RESOLVED)
        .orElseGet(() -> createOrRecoverFromRace(orderId, kind, detail));
  }

  private OperationsIncident createOrRecoverFromRace(
      UUID orderId, IncidentKind kind, String detail) {
    try {
      OperationsIncident incident = repository.save(new OperationsIncident(orderId, kind, detail));
      actionHistoryRepository.save(
          new IncidentActionHistory(
              incident.getIncidentId(), IncidentActionType.OPENED, SYSTEM_ACTOR, detail));
      projectionUpdater.recalculateOpenIncidentCount(orderId);
      return incident;
    } catch (DataIntegrityViolationException lostRace) {
      return repository
          .findByOrderIdAndKindAndStatusNot(orderId, kind, IncidentStatus.RESOLVED)
          .orElseThrow(() -> lostRace);
    }
  }
}
