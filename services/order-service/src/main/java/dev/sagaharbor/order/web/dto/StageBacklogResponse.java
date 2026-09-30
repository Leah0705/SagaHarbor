package dev.sagaharbor.order.web.dto;

public record StageBacklogResponse(
    String stage, long openOrderCount, long slaBreachedCount, double slaBreachRate) {}
