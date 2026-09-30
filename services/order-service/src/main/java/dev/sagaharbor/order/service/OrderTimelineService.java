package dev.sagaharbor.order.service;

import dev.sagaharbor.order.domain.IncidentActionHistory;
import dev.sagaharbor.order.domain.IncidentActionHistoryRepository;
import dev.sagaharbor.order.domain.OperationsIncident;
import dev.sagaharbor.order.domain.OperationsIncidentRepository;
import dev.sagaharbor.order.domain.OrderStatusHistory;
import dev.sagaharbor.order.domain.OrderStatusHistoryRepository;
import dev.sagaharbor.order.web.dto.OrderTimelineResponse;
import dev.sagaharbor.order.web.dto.TimelineEntryResponse;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Backs GET /api/v1/ops/orders/{orderId}/timeline — every status change plus every incident action
 * for one order, merged and sorted.
 */
@Service
public class OrderTimelineService {

  private final OrderStatusHistoryRepository statusHistoryRepository;
  private final OperationsIncidentRepository incidentRepository;
  private final IncidentActionHistoryRepository actionHistoryRepository;

  public OrderTimelineService(
      OrderStatusHistoryRepository statusHistoryRepository,
      OperationsIncidentRepository incidentRepository,
      IncidentActionHistoryRepository actionHistoryRepository) {
    this.statusHistoryRepository = statusHistoryRepository;
    this.incidentRepository = incidentRepository;
    this.actionHistoryRepository = actionHistoryRepository;
  }

  public OrderTimelineResponse timelineFor(UUID orderId) {
    List<TimelineEntryResponse> statusEntries =
        statusHistoryRepository.findByOrderIdOrderByOccurredAt(orderId).stream()
            .map(OrderTimelineService::toStatusEntry)
            .toList();

    List<UUID> incidentIds =
        incidentRepository.findByOrderId(orderId).stream()
            .map(OperationsIncident::getIncidentId)
            .toList();
    List<TimelineEntryResponse> incidentEntries =
        actionHistoryRepository.findByIncidentIdIn(incidentIds).stream()
            .map(OrderTimelineService::toIncidentEntry)
            .toList();

    List<TimelineEntryResponse> merged =
        java.util.stream.Stream.concat(statusEntries.stream(), incidentEntries.stream())
            .sorted(Comparator.comparing(TimelineEntryResponse::occurredAt))
            .toList();

    return new OrderTimelineResponse(orderId, merged);
  }

  private static TimelineEntryResponse toStatusEntry(OrderStatusHistory history) {
    return new TimelineEntryResponse(
        "STATUS_CHANGE",
        history.getOccurredAt(),
        history.getStatus().name(),
        history.getReasonCode(),
        null,
        null);
  }

  private static TimelineEntryResponse toIncidentEntry(IncidentActionHistory action) {
    return new TimelineEntryResponse(
        "INCIDENT_ACTION",
        action.getOccurredAt(),
        null,
        null,
        action.getActor(),
        action.getDetail());
  }
}
