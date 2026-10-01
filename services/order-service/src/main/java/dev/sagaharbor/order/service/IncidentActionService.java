package dev.sagaharbor.order.service;

import dev.sagaharbor.order.domain.IncidentActionHistory;
import dev.sagaharbor.order.domain.IncidentActionHistoryRepository;
import dev.sagaharbor.order.domain.IncidentActionType;
import dev.sagaharbor.order.domain.IncidentStatus;
import dev.sagaharbor.order.domain.OperationsIncident;
import dev.sagaharbor.order.domain.OperationsIncidentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The incident acknowledge/assign/resolve lifecycle — see docs/OPERATIONS_RUNBOOK.md for what each
 * action means operationally. Every action writes one incident_action_history row, so the full
 * lifecycle (including the OPENED row IncidentService writes automatically) is always
 * reconstructable.
 */
@Service
public class IncidentActionService {

  private final OperationsIncidentRepository incidentRepository;
  private final IncidentActionHistoryRepository actionHistoryRepository;
  private final OperationsProjectionUpdater projectionUpdater;

  public IncidentActionService(
      OperationsIncidentRepository incidentRepository,
      IncidentActionHistoryRepository actionHistoryRepository,
      OperationsProjectionUpdater projectionUpdater) {
    this.incidentRepository = incidentRepository;
    this.actionHistoryRepository = actionHistoryRepository;
    this.projectionUpdater = projectionUpdater;
  }

  @Transactional
  public OperationsIncident acknowledge(UUID incidentId, String actor) {
    OperationsIncident incident = requireUnresolvedIncident(incidentId);
    incident.acknowledge(actor);
    incidentRepository.save(incident);
    recordAction(incidentId, IncidentActionType.ACKNOWLEDGED, actor, null);
    return incident;
  }

  @Transactional
  public OperationsIncident assign(UUID incidentId, String actor, String assignee) {
    OperationsIncident incident = requireUnresolvedIncident(incidentId);
    incident.assign(assignee);
    incidentRepository.save(incident);
    recordAction(incidentId, IncidentActionType.ASSIGNED, actor, assignee);
    return incident;
  }

  @Transactional
  public OperationsIncident resolve(UUID incidentId, String actor, String resolutionNote) {
    OperationsIncident incident = requireUnresolvedIncident(incidentId);
    incident.resolve(actor, resolutionNote);
    incidentRepository.save(incident);
    recordAction(incidentId, IncidentActionType.RESOLVED, actor, resolutionNote);
    projectionUpdater.recalculateOpenIncidentCount(incident.getOrderId());
    return incident;
  }

  private OperationsIncident requireUnresolvedIncident(UUID incidentId) {
    OperationsIncident incident =
        incidentRepository
            .findById(incidentId)
            .orElseThrow(() -> new IncidentNotFoundException(incidentId));
    if (incident.getStatus() == IncidentStatus.RESOLVED) {
      throw new IncidentAlreadyResolvedException(incidentId);
    }
    return incident;
  }

  private void recordAction(
      UUID incidentId, IncidentActionType action, String actor, String detail) {
    actionHistoryRepository.save(new IncidentActionHistory(incidentId, action, actor, detail));
  }
}
