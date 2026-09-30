package dev.sagaharbor.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRequestRepository
    extends JpaRepository<IdempotencyRequest, IdempotencyRequestId> {}
