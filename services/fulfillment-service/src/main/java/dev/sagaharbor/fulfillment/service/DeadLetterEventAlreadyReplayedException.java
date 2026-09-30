package dev.sagaharbor.fulfillment.service;

import java.util.UUID;

public class DeadLetterEventAlreadyReplayedException extends RuntimeException {

  public DeadLetterEventAlreadyReplayedException(UUID eventId) {
    super("dead-lettered event " + eventId + " was already replayed");
  }
}
