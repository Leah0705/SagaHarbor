package dev.sagaharbor.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Records that this service has seen an order's OrderCancellationRequested.v1, so work that would
 * start afterwards is refused — see OrderCancellationGuard. Rows are only ever inserted, never
 * updated, so isNew() is always true and a save is always a real INSERT rather than a merge.
 */
@Entity
@Table(name = "order_cancellation_marker")
public class OrderCancellationMarker implements Persistable<UUID> {

  @Id private UUID orderId;

  private UUID correlationId;
  private Instant createdAt;

  protected OrderCancellationMarker() {
    // JPA
  }

  public OrderCancellationMarker(UUID orderId, UUID correlationId) {
    this.orderId = orderId;
    this.correlationId = correlationId;
    this.createdAt = Instant.now();
  }

  @Override
  public UUID getId() {
    return orderId;
  }

  @Override
  @Transient
  public boolean isNew() {
    return true;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
