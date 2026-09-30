package dev.sagaharbor.order.domain;

/** Matches incident_action_history.action's CHECK constraint in V4__operations.sql. */
public enum IncidentActionType {
  OPENED,
  ACKNOWLEDGED,
  ASSIGNED,
  RESOLVED
}
