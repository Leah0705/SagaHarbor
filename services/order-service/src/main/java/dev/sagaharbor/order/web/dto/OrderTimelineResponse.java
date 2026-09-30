package dev.sagaharbor.order.web.dto;

import java.util.List;
import java.util.UUID;

public record OrderTimelineResponse(UUID orderId, List<TimelineEntryResponse> entries) {}
