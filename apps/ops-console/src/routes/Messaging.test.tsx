import { describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { config } from "../config";
import { server } from "../test/server";
import { renderWithProviders } from "../test/renderWithProviders";
import { useIsAdmin } from "../auth/useIsAdmin";
import { Messaging } from "./Messaging";

vi.mock("react-oidc-context", () => ({
  useAuth: () => ({ user: { access_token: "test-token" } }),
}));
vi.mock("../demoMode/DemoModeContext", () => ({ useDemoMode: () => false }));
vi.mock("../auth/useIsAdmin", () => ({ useIsAdmin: vi.fn() }));

describe("Messaging", () => {
  it("shows partial failure without presenting a missing service as zero", async () => {
    vi.mocked(useIsAdmin).mockReturnValue(false);
    server.use(
      http.get(`${config.paymentServiceUrl}/api/v1/ops/messaging`, () =>
        HttpResponse.json({ title: "Unavailable", status: 503 }, { status: 503 }),
      ),
    );

    renderWithProviders(<Messaging />);

    expect(await screen.findByText("Some services are unavailable")).toBeInTheDocument();
    expect(screen.getAllByText("Unavailable").length).toBeGreaterThan(0);
    expect(screen.getByText("FulfillmentStatusChanged")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Replay" })).not.toBeInTheDocument();
  });

  it("sends an admin replay request only after confirmation", async () => {
    vi.mocked(useIsAdmin).mockReturnValue(true);
    let replayedEventId: string | null = null;
    server.use(
      http.post(
        `${config.orderServiceUrl}/api/v1/admin/dead-letters/:eventId/replay`,
        ({ params }) => {
          replayedEventId = String(params.eventId);
          return HttpResponse.json({ eventId: replayedEventId, status: "REPLAYED" });
        },
      ),
    );

    renderWithProviders(<Messaging />);

    expect(await screen.findByText("FulfillmentStatusChanged")).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "Replay" }));
    expect(replayedEventId).toBeNull();
    await userEvent
      .setup()
      .click(within(screen.getByRole("dialog")).getByRole("button", { name: "Replay" }));
    await waitFor(() => expect(replayedEventId).toBe("66666666-6666-4666-8666-666666666601"));
    expect(await screen.findByText("Replay submitted")).toBeInTheDocument();
  });
});
