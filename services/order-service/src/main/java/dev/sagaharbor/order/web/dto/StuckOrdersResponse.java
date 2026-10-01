package dev.sagaharbor.order.web.dto;

import java.util.List;

public record StuckOrdersResponse(long totalStuckOrders, List<AgeBucketResponse> ageBuckets) {}
