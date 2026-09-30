package dev.sagaharbor.order.web.dto;

import java.time.Instant;

public record TimeSeriesPointResponse(
    Instant bucketStart, long ordersReceived, long ordersCompleted, long ordersCancelled) {}
