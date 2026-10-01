package dev.sagaharbor.inventory.web.dto;

import java.time.Instant;

public record AvailabilityResponse(
    String sku, int availableQuantity, int reservedQuantity, Instant updatedAt) {}
