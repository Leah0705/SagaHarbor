package dev.sagaharbor.order.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AssignIncidentRequest(@NotBlank String assignee) {}
