package dev.sagaharbor.payment.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderCancellationMarkerRepository
    extends JpaRepository<OrderCancellationMarker, UUID> {

  /**
   * Takes a transaction-scoped Postgres advisory lock on key, waiting if another transaction holds
   * it. The lock is released when the calling transaction commits or rolls back. Wrapped in a
   * count(*) because pg_advisory_xact_lock returns void, which has no JDBC mapping.
   */
  @Query(
      value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS order_lock",
      nativeQuery = true)
  long acquireTransactionLock(@Param("key") long key);
}
