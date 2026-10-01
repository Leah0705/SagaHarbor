package dev.sagaharbor.order.web.dto;

import dev.sagaharbor.order.domain.OrderReviewDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The note is required: it becomes the resolution note on the order's review incidents, so the
 * incident history records why the operator decided this way.
 */
public record ReviewDecisionRequest(
    @NotNull OrderReviewDecision decision, @NotBlank @Size(max = 400) String note) {}
