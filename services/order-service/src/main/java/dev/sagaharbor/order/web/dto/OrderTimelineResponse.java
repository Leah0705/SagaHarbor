package dev.sagaharbor.order.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * reviewResumeStatus is set only while the order waits in REQUIRES_REVIEW: the status an operator
 * RESUME would return it to (see OrderReviewService).
 */
public record OrderTimelineResponse(
    UUID orderId, List<TimelineEntryResponse> entries, String reviewResumeStatus) {}
