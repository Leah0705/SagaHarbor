package dev.sagaharbor.order.web.dto;

import java.time.Instant;
import java.util.List;

public record StageDurationKpiResponse(
    Instant from,
    Instant to,
    List<StagePercentilesResponse> stages,
    CycleTimePercentilesResponse endToEndCycleTime) {}
