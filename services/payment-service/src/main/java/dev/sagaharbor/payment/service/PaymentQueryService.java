package dev.sagaharbor.payment.service;

import dev.sagaharbor.payment.domain.Payment;
import dev.sagaharbor.payment.domain.PaymentAttempt;
import dev.sagaharbor.payment.domain.PaymentAttemptRepository;
import dev.sagaharbor.payment.domain.PaymentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Read side of Payment Service: payment status lookups and their attempt history. */
@Service
public class PaymentQueryService {

  private final PaymentRepository paymentRepository;
  private final PaymentAttemptRepository paymentAttemptRepository;

  public PaymentQueryService(
      PaymentRepository paymentRepository, PaymentAttemptRepository paymentAttemptRepository) {
    this.paymentRepository = paymentRepository;
    this.paymentAttemptRepository = paymentAttemptRepository;
  }

  public Optional<Payment> findPayment(UUID paymentId) {
    return paymentRepository.findById(paymentId);
  }

  public List<PaymentAttempt> findAttempts(UUID paymentId) {
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    return paymentAttemptRepository.findByOrderIdOrderByAttemptNumber(payment.getOrderId());
  }
}
