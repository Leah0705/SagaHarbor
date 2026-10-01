package dev.sagaharbor.payment.service;

import dev.sagaharbor.payment.domain.IdempotencyRequest;
import dev.sagaharbor.payment.domain.IdempotencyRequestId;
import dev.sagaharbor.payment.domain.IdempotencyRequestRepository;
import dev.sagaharbor.payment.domain.Payment;
import dev.sagaharbor.payment.domain.PaymentRepository;
import dev.sagaharbor.payment.domain.PaymentStatus;
import dev.sagaharbor.payment.domain.Refund;
import dev.sagaharbor.payment.domain.RefundReasonCode;
import dev.sagaharbor.payment.domain.RefundRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a refund request: an idempotency-key replay check (same shape as inventory-service's
 * StockAdjustmentService), then one RefundTransaction attempt. A refund is idempotent in two senses
 * at once: the Idempotency-Key protects a single caller against its own retried request, and
 * refunds.payment_id being UNIQUE protects against two different callers (or two different keys)
 * both trying to refund the same payment.
 */
@Service
public class RefundService {

  private final IdempotencyRequestRepository idempotencyRequestRepository;
  private final RefundRepository refundRepository;
  private final PaymentRepository paymentRepository;
  private final RefundTransaction refundTransaction;

  public RefundService(
      IdempotencyRequestRepository idempotencyRequestRepository,
      RefundRepository refundRepository,
      PaymentRepository paymentRepository,
      RefundTransaction refundTransaction) {
    this.idempotencyRequestRepository = idempotencyRequestRepository;
    this.refundRepository = refundRepository;
    this.paymentRepository = paymentRepository;
    this.refundTransaction = refundTransaction;
  }

  public Refund refund(
      String actorId,
      String idempotencyKey,
      UUID paymentId,
      RefundReasonCode reasonCode,
      UUID correlationId) {
    String fingerprint = fingerprint(actorId, paymentId, reasonCode);
    IdempotencyRequestId idempotencyRequestId = new IdempotencyRequestId(actorId, idempotencyKey);

    Optional<IdempotencyRequest> existing =
        idempotencyRequestRepository.findById(idempotencyRequestId);
    if (existing.isPresent()) {
      return replayOrConflict(existing.get(), idempotencyKey, fingerprint);
    }

    try {
      return refundTransaction.attempt(
          paymentId, actorId, idempotencyKey, reasonCode.name(), correlationId, fingerprint);
    } catch (DataIntegrityViolationException lostRace) {
      return resolveAfterLostRace(
          idempotencyRequestId, idempotencyKey, fingerprint, paymentId, lostRace);
    }
  }

  /**
   * The event-driven counterpart to refund(...): no Idempotency-Key dance, since Kafka's own inbox
   * dedup already makes redelivery of the same event safe, and refunds.payment_id being UNIQUE
   * makes a second, differently-triggered refund attempt for the same payment safe too — attempt()
   * re-checks isAuthorized() itself and throws InvalidRefundStateException if some other trigger
   * already resolved it first, which is exactly the no-op this method wants in that case. A missing
   * or already-non-authorized payment is also a no-op: cancellation may be requested before payment
   * was ever authorized, or another compensation path may have already refunded it.
   */
  public void refundForCompensation(UUID orderId, RefundReasonCode reasonCode, UUID correlationId) {
    Optional<Payment> payment = paymentRepository.findByOrderId(orderId);
    if (payment.isEmpty() || !payment.get().isAuthorized()) {
      return;
    }

    UUID paymentId = payment.get().getPaymentId();
    String idempotencyKey = "auto-" + orderId;
    String fingerprint = fingerprint("system", paymentId, reasonCode);
    try {
      refundTransaction.attempt(
          paymentId, "system", idempotencyKey, reasonCode.name(), correlationId, fingerprint);
    } catch (InvalidRefundStateException alreadyResolved) {
      // A concurrent trigger (a direct operator cancel, a different compensation path) already
      // refunded or otherwise resolved this payment first — nothing left to do.
    }
  }

  /**
   * Answers OrderCancellationRequested.v1. An authorized payment is refunded. A payment refunded
   * earlier gets its PaymentRefunded.v1 published again, because Order Service asks again only when
   * it is still waiting, which means the first answer never reached it. No payment means nothing to
   * answer: the cancellation marker the caller wrote first stops one from being authorized later.
   */
  public void compensateCancellationRequest(UUID orderId, UUID correlationId) {
    Optional<Payment> payment = paymentRepository.findByOrderId(orderId);
    if (payment.isPresent() && payment.get().getStatus() == PaymentStatus.REFUNDED) {
      refundTransaction.republishRefunded(payment.get().getPaymentId(), correlationId);
      return;
    }
    refundForCompensation(orderId, RefundReasonCode.ORDER_CANCELLED, correlationId);
  }

  private Refund resolveAfterLostRace(
      IdempotencyRequestId idempotencyRequestId,
      String idempotencyKey,
      String fingerprint,
      UUID paymentId,
      DataIntegrityViolationException lostRace) {
    Optional<IdempotencyRequest> winner =
        idempotencyRequestRepository.findById(idempotencyRequestId);
    if (winner.isPresent()) {
      return replayOrConflict(winner.get(), idempotencyKey, fingerprint);
    }

    // Not an Idempotency-Key race: a different actor or a different key raced this one for the
    // same payment and won refunds.payment_id's unique constraint first.
    Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> lostRace);
    if (!payment.isAuthorized()) {
      throw new InvalidRefundStateException(
          "payment "
              + paymentId
              + " is not refundable (current status: "
              + payment.getStatus()
              + ")");
    }
    throw lostRace;
  }

  private Refund replayOrConflict(
      IdempotencyRequest existing, String idempotencyKey, String fingerprint) {
    if (!existing.getRequestFingerprint().equals(fingerprint)) {
      throw new IdempotencyKeyConflictException(idempotencyKey);
    }
    return refundRepository
        .findById(existing.getRefundId())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "idempotency_requests row references a missing refund: "
                        + existing.getRefundId()));
  }

  private static String fingerprint(String actorId, UUID paymentId, RefundReasonCode reasonCode) {
    return RequestFingerprint.sha256Hex(actorId + '|' + paymentId + '|' + reasonCode);
  }
}
