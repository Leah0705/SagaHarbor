package dev.sagaharbor.inventory.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  // Backlog = not yet published (PENDING still waiting its turn, or FAILED after exhausting
  // OutboxRelay's own retry budget) — see OutboxBacklogMetrics. state is plain String on
  // OutboxEvent (not @Enumerated), so these bind a String, not OutboxState directly —
  // OutboxState.PUBLISHED.name() at the call site.
  long countByStateNot(String state);

  Optional<OutboxEvent> findFirstByStateNotOrderByCreatedAtAsc(String state);

  /**
   * Claims a bounded batch of due rows for this poll cycle. A due row is PENDING, or FAILED after
   * using up OutboxRelay's fast retry budget. FAILED rows keep being retried at the capped backoff,
   * so the backlog drains on its own once Kafka is reachable again. FOR UPDATE SKIP LOCKED holds
   * the row locks only for this short transaction, which ends before OutboxRelay sends to Kafka, so
   * a second relay instance can claim and publish the same rows. The README's "Known limitations"
   * section covers that case.
   */
  @Transactional
  @Query(
      value =
          """
            SELECT * FROM outbox_event
            WHERE state IN ('PENDING', 'FAILED')
              AND (next_attempt_at IS NULL OR next_attempt_at <= now())
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """,
      nativeQuery = true)
  List<OutboxEvent> claimBatch(@Param("batchSize") int batchSize);

  @Transactional
  @Modifying
  @Query(
      "UPDATE OutboxEvent o SET o.state = 'PUBLISHED', o.publishedAt = :publishedAt WHERE o.eventId = :eventId")
  void markPublished(@Param("eventId") UUID eventId, @Param("publishedAt") Instant publishedAt);

  @Transactional
  @Modifying
  @Query(
      """
            UPDATE OutboxEvent o
            SET o.attemptCount = o.attemptCount + 1,
                o.lastError = :error,
                o.nextAttemptAt = :nextAttemptAt,
                o.state = CASE WHEN o.attemptCount + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END
            WHERE o.eventId = :eventId
            """)
  void markFailedAttempt(
      @Param("eventId") UUID eventId,
      @Param("error") String error,
      @Param("nextAttemptAt") Instant nextAttemptAt,
      @Param("maxAttempts") int maxAttempts);
}
