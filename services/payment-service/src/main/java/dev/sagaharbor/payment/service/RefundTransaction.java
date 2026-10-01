package dev.sagaharbor.payment.service;

import dev.sagaharbor.payment.domain.IdempotencyRequest;
import dev.sagaharbor.payment.domain.IdempotencyRequestRepository;
import dev.sagaharbor.payment.domain.Payment;
import dev.sagaharbor.payment.domain.PaymentRepository;
import dev.sagaharbor.payment.domain.Refund;
import dev.sagaharbor.payment.domain.RefundRepository;
import dev.sagaharbor.payment.messaging.MoneyPayload;
import dev.sagaharbor.payment.messaging.OutboxEventWriter;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes exactly one refund attempt: refunds the payment's full original amount (never a
 * client-supplied one), flips the payment to REFUNDED, and writes the PaymentRefunded.v1 outbox
 * event, all atomically. refunds.payment_id is UNIQUE at the database level (see V2__payments.sql),
 * so a second concurrent attempt for the same payment loses this transaction's flush and
 * RefundService is what tells a legitimate replay apart from a genuine conflict.
 */
@Component
public class RefundTransaction {

  private static final String REFUNDED_EVENT_TYPE = "PaymentRefunded";
  private static final int EVENT_VERSION = 1;

  private final PaymentRepository paymentRepository;
  private final RefundRepository refundRepository;
  private final IdempotencyRequestRepository idempotencyRequestRepository;
  private final OutboxEventWriter outboxEventWriter;

  public RefundTransaction(
      PaymentRepository paymentRepository,
      RefundRepository refundRepository,
      IdempotencyRequestRepository idempotencyRequestRepository,
      OutboxEventWriter outboxEventWriter) {
    this.paymentRepository = paymentRepository;
    this.refundRepository = refundRepository;
    this.idempotencyRequestRepository = idempotencyRequestRepository;
    this.outboxEventWriter = outboxEventWriter;
  }

  @Transactional
  public Refund attempt(
      UUID paymentId,
      String actorId,
      String idempotencyKey,
      String reasonCode,
      UUID correlationId,
      String requestFingerprint) {
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    if (!payment.isAuthorized()) {
      throw new InvalidRefundStateException(
          "payment "
              + paymentId
              + " is not refundable (current status: "
              + payment.getStatus()
              + ")");
    }

    UUID refundId = UUID.randomUUID();
    Refund refund =
        new Refund(
            refundId,
            paymentId,
            payment.getAmount(),
            payment.getCurrencyCode(),
            reasonCode,
            correlationId);
    refundRepository.save(refund);

    payment.markRefunded();
    paymentRepository.save(payment);

    idempotencyRequestRepository.save(
        new IdempotencyRequest(actorId, idempotencyKey, requestFingerprint, refundId));

    outboxEventWriter.write(
        REFUNDED_EVENT_TYPE,
        EVENT_VERSION,
        payment.getOrderId(),
        correlationId,
        null,
        new RefundedPayload(
            paymentId,
            MoneyPayload.of(payment.getCurrencyCode(), payment.getAmount()),
            reasonCode));

    return refund;
  }

  /**
   * Publishes PaymentRefunded.v1 again for a refund made earlier, with the same payment, amount and
   * reason. Nothing else changes; Order Service treats the repeat like the original.
   */
  @Transactional
  public void republishRefunded(UUID paymentId, UUID correlationId) {
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    Refund refund =
        refundRepository
            .findByPaymentId(paymentId)
            .orElseThrow(
                () ->
                    new IllegalStateException("refunded payment " + paymentId + " has no refund"));
    outboxEventWriter.write(
        REFUNDED_EVENT_TYPE,
        EVENT_VERSION,
        payment.getOrderId(),
        correlationId,
        null,
        new RefundedPayload(
            paymentId,
            MoneyPayload.of(refund.getCurrencyCode(), refund.getAmount()),
            refund.getReasonCode()));
  }

  private record RefundedPayload(UUID paymentId, MoneyPayload amount, String reasonCode) {}
}
