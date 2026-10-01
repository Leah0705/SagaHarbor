import { Button, Card, Group, Text, Title } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { notifications } from "@mantine/notifications";
import { ConfirmModal } from "./ConfirmModal";
import { useReviewDecision } from "../api/hooks";
import { formatStatusLabel } from "../format";
import type { OrderStatus, ReviewDecision } from "../api/types";

const HAPPY_PATH: OrderStatus[] = [
  "PENDING",
  "INVENTORY_RESERVED",
  "PAYMENT_AUTHORIZED",
  "FULFILLMENT_ASSIGNED",
  "PICKING",
  "PACKED",
  "DISPATCHED",
  "DELIVERED",
];
const LEFT_WAREHOUSE: OrderStatus[] = ["DISPATCHED", "DELIVERED"];

// Mirrors the rules in order-service's OrderReviewService so the console only offers decisions
// the order can take; the backend enforces them and answers 409 otherwise.
function explain(resumeStatus: OrderStatus | null): string {
  if (resumeStatus === null) {
    return "This order is waiting for an operator, but the status it came from was not recorded.";
  }
  if (resumeStatus === "CANCELLATION_PENDING") {
    return "Its cancellation ran out of retries. Retrying asks every service to confirm its compensation again.";
  }
  if (LEFT_WAREHOUSE.includes(resumeStatus)) {
    return `The goods have left the warehouse, so the order can only resume at ${formatStatusLabel(resumeStatus)}.`;
  }
  return `Resume returns the order to ${formatStatusLabel(resumeStatus)}. Cancelling releases stock, refunds the payment and cancels the fulfillment as needed.`;
}

export function ReviewDecisionPanel({
  orderId,
  resumeStatus,
}: {
  orderId: string;
  resumeStatus: OrderStatus | null;
}) {
  const [resumeOpened, resumeHandlers] = useDisclosure(false);
  const [cancelOpened, cancelHandlers] = useDisclosure(false);
  const decide = useReviewDecision(orderId);

  const canResume = resumeStatus !== null && HAPPY_PATH.includes(resumeStatus);
  const canCancel = resumeStatus !== null && !LEFT_WAREHOUSE.includes(resumeStatus);
  const cancelLabel =
    resumeStatus === "CANCELLATION_PENDING" ? "Retry cancellation" : "Cancel with compensation";

  function submit(decision: ReviewDecision, note: string, close: () => void) {
    decide.mutate(
      { decision, note },
      {
        onSuccess: close,
        onError: () =>
          notifications.show({
            color: "red",
            title: "Decision failed",
            message: "The order may have changed. Reload it and try again.",
          }),
      },
    );
  }

  return (
    <Card withBorder padding="md">
      <Title order={2} size="h4" mb="xs">
        Review decision
      </Title>
      <Text size="sm" c="dimmed" mb="sm">
        {explain(resumeStatus)}
      </Text>
      <Group gap="xs">
        <Button variant="light" onClick={resumeHandlers.open} disabled={!canResume}>
          Resume
        </Button>
        <Button variant="light" color="red" onClick={cancelHandlers.open} disabled={!canCancel}>
          {cancelLabel}
        </Button>
      </Group>

      <ConfirmModal
        opened={resumeOpened}
        onClose={resumeHandlers.close}
        title="Resume order"
        description="The order continues from where the other services left it. The note is saved on its review incidents."
        confirmLabel="Resume"
        confirmColor="indigo"
        requireReason
        reasonLabel="Decision note"
        isSubmitting={decide.isPending}
        onConfirm={(note) => submit("RESUME", note, resumeHandlers.close)}
      />
      <ConfirmModal
        opened={cancelOpened}
        onClose={cancelHandlers.close}
        title={cancelLabel}
        description="Every service is asked to undo what it holds for this order. The note is saved on its review incidents."
        confirmLabel={cancelLabel}
        requireReason
        reasonLabel="Decision note"
        isSubmitting={decide.isPending}
        onConfirm={(note) => submit("CANCEL", note, cancelHandlers.close)}
      />
    </Card>
  );
}
