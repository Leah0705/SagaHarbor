package dev.sagaharbor.payment.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderPaymentContextRepository extends JpaRepository<OrderPaymentContext, UUID> {}
