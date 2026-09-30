import { config } from "../config";
import { requestJson } from "./httpClient";
import type { DeadLetterEventResponse } from "./types";

const services = [
  { id: "order", label: "Order", baseUrl: config.orderServiceUrl },
  { id: "inventory", label: "Inventory", baseUrl: config.inventoryServiceUrl },
  { id: "payment", label: "Payment", baseUrl: config.paymentServiceUrl },
  { id: "fulfillment", label: "Fulfillment", baseUrl: config.fulfillmentServiceUrl },
] as const;

export type ServiceId = (typeof services)[number]["id"];

export interface PendingDeadLetterSummary {
  eventId: string;
  consumerName: string;
  originalTopic: string;
  eventType: string;
  aggregateId: string;
  createdAt: string;
}

export interface MessagingSnapshot {
  pendingDeadLetterCount: number;
  outboxBacklogCount: number;
  oldestOutboxEventAt: string | null;
  deadLetters: PendingDeadLetterSummary[];
}

export interface FleetMessagingResult {
  service: ServiceId;
  label: string;
  snapshot: MessagingSnapshot | null;
  error: unknown | null;
}

function serviceUrl(service: ServiceId): string {
  return services.find((item) => item.id === service)!.baseUrl;
}

export async function fetchFleetMessaging(accessToken: string): Promise<FleetMessagingResult[]> {
  const results = await Promise.allSettled(
    services.map((service) =>
      requestJson<MessagingSnapshot>(service.baseUrl, "/api/v1/ops/messaging", { accessToken }),
    ),
  );
  return services.map((service, index) => {
    const result = results[index];
    return result.status === "fulfilled"
      ? { service: service.id, label: service.label, snapshot: result.value, error: null }
      : { service: service.id, label: service.label, snapshot: null, error: result.reason };
  });
}

export function replayFleetDeadLetter(accessToken: string, service: ServiceId, eventId: string) {
  return requestJson<DeadLetterEventResponse>(
    serviceUrl(service),
    `/api/v1/admin/dead-letters/${eventId}/replay`,
    { method: "POST", accessToken },
  );
}
