package dev.sagaharbor.fulfillment.service;

public class InvalidFulfillmentRequestException extends RuntimeException {

  public InvalidFulfillmentRequestException(String message) {
    super(message);
  }
}
