import { Text } from "@mantine/core";
import type { IncidentKind } from "../api/types";

// Presentational only — the backend has no "severity" concept for IncidentKind. This is
// a client-side triage aid, not a new backend concept.
const SEVERITY: Record<IncidentKind, { label: string; color: string }> = {
  COMPENSATION_EXHAUSTED: { label: "High", color: "red" },
  CANCELLATION_AFTER_DISPATCH: { label: "Medium", color: "orange" },
  CANCELLATION_STUCK: { label: "Low", color: "yellow" },
  SLA_BREACHED: { label: "Low", color: "yellow" },
};

export function SeverityBadge({ kind }: { kind: IncidentKind }) {
  const severity = SEVERITY[kind];
  return (
    <Text component="span" size="sm" fw={600} c={`${severity.color}.9`}>
      {severity.label}
    </Text>
  );
}
