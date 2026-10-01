package dev.sagaharbor.order.web.dto;

import java.time.Instant;

public record LowStockSignalResponse(
    String sku, int availableQuantity, int threshold, boolean belowThreshold, Instant occurredAt) {}
