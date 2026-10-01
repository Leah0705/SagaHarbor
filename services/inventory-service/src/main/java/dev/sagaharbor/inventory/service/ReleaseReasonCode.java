package dev.sagaharbor.inventory.service;

/** Matches InventoryReleased.v1's closed reasonCode enum in contracts/events/. */
public enum ReleaseReasonCode {
  PAYMENT_DECLINED,
  FULFILLMENT_CANCELLED,
  ORDER_CANCELLED
}
