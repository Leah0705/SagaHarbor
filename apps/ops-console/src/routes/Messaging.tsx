import { Alert, Button, Card, Group, Stack, Table, Text, Title } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { notifications } from "@mantine/notifications";
import { useState } from "react";
import { Link } from "react-router-dom";
import { useFleetMessaging, useReplayFleetDeadLetter } from "../api/hooks";
import type { PendingDeadLetterSummary, ServiceId } from "../api/messagingApi";
import { useIsAdmin } from "../auth/useIsAdmin";
import { ConfirmModal } from "../components/ConfirmModal";
import { EmptyState, ErrorState, LoadingState } from "../components/QueryState";
import { PageHeading } from "../components/PageHeading";
import { useDemoMode } from "../demoMode/DemoModeContext";
import { formatDateTime } from "../format";

interface SelectedEvent {
  service: ServiceId;
  eventId: string;
}

interface FleetEvent {
  service: ServiceId;
  label: string;
  event: PendingDeadLetterSummary;
}

export function Messaging() {
  const isDemo = useDemoMode();
  const isAdmin = useIsAdmin();
  const fleet = useFleetMessaging();
  const replay = useReplayFleetDeadLetter();
  const [opened, handlers] = useDisclosure(false);
  const [selected, setSelected] = useState<SelectedEvent | null>(null);
  const [lastReplay, setLastReplay] = useState<string | null>(null);

  const unavailable = fleet.data?.filter((item) => !item.snapshot) ?? [];
  const events: FleetEvent[] = (fleet.data ?? [])
    .flatMap((item) =>
      (item.snapshot?.deadLetters ?? []).map((event) => ({
        service: item.service,
        label: item.label,
        event,
      })),
    )
    .sort((a, b) => b.event.createdAt.localeCompare(a.event.createdAt));

  return (
    <Stack gap="lg">
      <Group justify="space-between">
        <PageHeading>Messaging</PageHeading>
        {!isDemo && (
          <Button variant="light" onClick={() => fleet.refetch()} loading={fleet.isFetching}>
            Refresh
          </Button>
        )}
      </Group>

      {isDemo && (
        <Alert color="blue" title="Live backend required">
          Connect all four services to view their messaging state and replay dead letters.
        </Alert>
      )}
      {!isDemo && fleet.isLoading && <LoadingState label="Loading messaging status" />}
      {!isDemo && fleet.isError && <ErrorState error={fleet.error} />}
      {unavailable.length > 0 && (
        <Alert color="red" title="Some services are unavailable">
          {unavailable.map((item) => item.label).join(", ")} could not be read. Their counts are
          unavailable, not zero.
        </Alert>
      )}
      {lastReplay && (
        <Alert color="blue" title="Replay submitted">
          {lastReplay}. Check the order timeline and refresh this view to verify downstream
          recovery.
        </Alert>
      )}

      {fleet.data && (
        <Card withBorder padding="md">
          <Title order={2} size="h4" mb="sm">
            Service backlog
          </Title>
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Service</Table.Th>
                <Table.Th>Dead letters pending</Table.Th>
                <Table.Th>Outbox unpublished</Table.Th>
                <Table.Th>Oldest outbox event</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {fleet.data.map((item) => (
                <Table.Tr key={item.service}>
                  <Table.Td>{item.label}</Table.Td>
                  <Table.Td>{item.snapshot?.pendingDeadLetterCount ?? "Unavailable"}</Table.Td>
                  <Table.Td>{item.snapshot?.outboxBacklogCount ?? "Unavailable"}</Table.Td>
                  <Table.Td>
                    {item.snapshot
                      ? item.snapshot.oldestOutboxEventAt
                        ? formatDateTime(item.snapshot.oldestOutboxEventAt)
                        : "None"
                      : "Unavailable"}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Card>
      )}

      {fleet.data && (
        <Card withBorder padding="md">
          <Title order={2} size="h4" mb="sm">
            Pending dead letters
          </Title>
          <Text size="xs" c="dimmed" mb="sm">
            Showing the latest 50 per service. Counts above include every pending event.
          </Text>
          {events.length === 0 && (
            <EmptyState message="No pending dead letters in reachable services." />
          )}
          {events.length > 0 && (
            <Table>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Service</Table.Th>
                  <Table.Th>Event</Table.Th>
                  <Table.Th>Related order</Table.Th>
                  <Table.Th>Consumer</Table.Th>
                  <Table.Th>Created</Table.Th>
                  {isAdmin && <Table.Th>Action</Table.Th>}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {events.map(({ service, label, event }) => (
                  <Table.Tr key={`${service}-${event.eventId}-${event.consumerName}`}>
                    <Table.Td>{label}</Table.Td>
                    <Table.Td>{event.eventType}</Table.Td>
                    <Table.Td>
                      {event.eventType === "InventoryLowStock" ? (
                        <Text size="sm">SKU event</Text>
                      ) : (
                        <Text
                          component={Link}
                          to={`/orders/${event.aggregateId}`}
                          size="sm"
                          c="indigo.9"
                        >
                          {event.aggregateId.slice(0, 8)}…
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>{event.consumerName}</Table.Td>
                    <Table.Td>{formatDateTime(event.createdAt)}</Table.Td>
                    {isAdmin && (
                      <Table.Td>
                        <Button
                          size="xs"
                          variant="light"
                          onClick={() => {
                            setSelected({ service, eventId: event.eventId });
                            handlers.open();
                          }}
                        >
                          Replay
                        </Button>
                      </Table.Td>
                    )}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </Card>
      )}

      <ConfirmModal
        opened={opened}
        onClose={handlers.close}
        title="Replay dead letter"
        description="This publishes the stored event again. Fix the underlying cause first; a successful request does not prove downstream recovery."
        confirmLabel="Replay"
        confirmColor="indigo"
        isSubmitting={replay.isPending}
        onConfirm={() => {
          if (!selected) return;
          replay.mutate(selected, {
            onSuccess: (result) => {
              setLastReplay(`${selected.service} event ${result.eventId}: ${result.status}`);
              handlers.close();
            },
            onError: () =>
              notifications.show({
                color: "red",
                title: "Replay failed",
                message: "The event was not replayed. Check the service and try again.",
              }),
          });
        }}
      />
    </Stack>
  );
}
