import { type ReactNode } from "react";
import { AppShell, Group, NavLink, Stack, Text, Title, Button, Alert, Burger } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import {
  IconAlertTriangle,
  IconBoxSeam,
  IconLayoutDashboard,
  IconListDetails,
  IconLogout,
  IconTruckDelivery,
} from "@tabler/icons-react";
import { NavLink as RouterNavLink, useLocation } from "react-router-dom";
import { useAuth } from "react-oidc-context";
import { useDemoMode } from "../demoMode/DemoModeContext";

const NAV_ITEMS = [
  { to: "/", label: "Overview", icon: IconLayoutDashboard },
  { to: "/work-queue", label: "Work Queue", icon: IconListDetails },
  { to: "/incidents", label: "Incidents", icon: IconAlertTriangle },
  { to: "/inventory-risk", label: "Inventory Risk", icon: IconBoxSeam },
  { to: "/fulfillment-board", label: "Fulfillment Board", icon: IconTruckDelivery },
  { to: "/messaging", label: "Messaging", icon: IconAlertTriangle },
];

function SignOutButton() {
  const auth = useAuth();
  return (
    <Button
      variant="subtle"
      color="gray"
      leftSection={<IconLogout size={16} />}
      onClick={() => auth.signoutRedirect()}
    >
      Sign out
    </Button>
  );
}

export function Shell({ children }: { children: ReactNode }) {
  const location = useLocation();
  const isDemoMode = useDemoMode();
  const [mobileOpened, { toggle, close }] = useDisclosure(false);

  return (
    <AppShell
      header={{ height: 60 }}
      navbar={{ width: 240, breakpoint: "sm", collapsed: { mobile: !mobileOpened } }}
      padding="md"
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Group gap="sm">
            <Burger
              opened={mobileOpened}
              onClick={toggle}
              hiddenFrom="sm"
              size="sm"
              aria-label="Toggle navigation"
            />
            <Title order={1} size="h3">
              SagaHarbor
            </Title>
          </Group>
          {!isDemoMode && <SignOutButton />}
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="md">
        <Stack gap="xs" component="nav" aria-label="Primary">
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              component={RouterNavLink}
              to={item.to}
              label={item.label}
              leftSection={<item.icon size={18} stroke={1.5} />}
              active={location.pathname === item.to}
              onClick={close}
            />
          ))}
        </Stack>
      </AppShell.Navbar>

      <AppShell.Main>
        {isDemoMode && (
          <Alert
            color="yellow"
            variant="light"
            mb="md"
            title="Demo data"
            role="status"
            data-testid="demo-mode-banner"
          >
            <Text size="sm">
              The real backend is unreachable, so this console is showing static example data. No
              action taken here affects any real system.
            </Text>
          </Alert>
        )}
        {children}
      </AppShell.Main>
    </AppShell>
  );
}
