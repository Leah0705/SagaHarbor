package dev.sagaharbor.fulfillment.service;

import dev.sagaharbor.fulfillment.domain.FulfillmentStatus;

public class InvalidFulfillmentTransitionException extends RuntimeException {

  public InvalidFulfillmentTransitionException(FulfillmentStatus from, FulfillmentStatus to) {
    super("cannot move a fulfillment from " + from + " to " + to);
  }
}
