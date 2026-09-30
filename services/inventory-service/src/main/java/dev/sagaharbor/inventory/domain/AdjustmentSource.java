package dev.sagaharbor.inventory.domain;

/** Matches the inventory_adjustment.source CHECK constraint. */
public enum AdjustmentSource {
  ADMIN_ADJUSTMENT,
  RESERVATION,
  RELEASE
}
