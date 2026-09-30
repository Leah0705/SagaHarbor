package dev.sagaharbor.order.web;

import dev.sagaharbor.order.messaging.DeadLetterEvent;
import dev.sagaharbor.order.messaging.DeadLetterEventRepository;
import dev.sagaharbor.order.messaging.DeadLetterEventStatus;
import dev.sagaharbor.order.messaging.OutboxEventRepository;
import dev.sagaharbor.order.messaging.OutboxState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only messaging health for OPERATOR and ADMIN. Replay remains ADMIN-only. */
@RestController
@RequestMapping("/api/v1/ops/messaging")
public class MessagingOpsController {

  private final DeadLetterEventRepository deadLetters;
  private final OutboxEventRepository outbox;

  public MessagingOpsController(
      DeadLetterEventRepository deadLetters, OutboxEventRepository outbox) {
    this.deadLetters = deadLetters;
    this.outbox = outbox;
  }

  @GetMapping
  public MessagingSnapshot snapshot() {
    var pending = DeadLetterEventStatus.PENDING_REVIEW;
    return new MessagingSnapshot(
        deadLetters.countByStatus(pending),
        outbox.countByStateNot(OutboxState.PUBLISHED.name()),
        outbox
            .findFirstByStateNotOrderByCreatedAtAsc(OutboxState.PUBLISHED.name())
            .map(event -> event.getOccurredAt())
            .orElse(null),
        deadLetters.findTop50ByStatusOrderByCreatedAtDesc(pending).stream()
            .map(PendingDeadLetterSummary::from)
            .toList());
  }

  /** The list is capped at 50; the count always covers every pending event. */
  public record MessagingSnapshot(
      long pendingDeadLetterCount,
      long outboxBacklogCount,
      Instant oldestOutboxEventAt,
      List<PendingDeadLetterSummary> deadLetters) {}

  /** OPERATOR receives routing metadata, never the stored message body. */
  public record PendingDeadLetterSummary(
      UUID eventId,
      String consumerName,
      String originalTopic,
      String eventType,
      UUID aggregateId,
      Instant createdAt) {
    public static PendingDeadLetterSummary from(DeadLetterEvent event) {
      return new PendingDeadLetterSummary(
          event.getId().getEventId(),
          event.getId().getConsumerName(),
          event.getOriginalTopic(),
          event.getEventType(),
          event.getAggregateId(),
          event.getCreatedAt());
    }
  }
}
