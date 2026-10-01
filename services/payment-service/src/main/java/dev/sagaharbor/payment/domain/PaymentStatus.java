package dev.sagaharbor.payment.domain;

/** A payment's lifecycle: AUTHORIZED, then optionally REFUNDED. DECLINED is terminal. */
public enum PaymentStatus {
  AUTHORIZED,
  DECLINED,
  REFUNDED
}
