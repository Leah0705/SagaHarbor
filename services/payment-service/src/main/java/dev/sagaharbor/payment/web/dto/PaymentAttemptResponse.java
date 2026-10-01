package dev.sagaharbor.payment.web.dto;

import java.time.Instant;

public record PaymentAttemptResponse(
    int attemptNumber, String outcome, String detail, Instant createdAt) {}
