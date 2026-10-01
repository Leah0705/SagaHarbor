package dev.sagaharbor.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRequestRepository
    extends JpaRepository<IdempotencyRequest, IdempotencyRequestId> {}
