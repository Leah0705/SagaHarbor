import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { config } from "../config";
import { server } from "../test/server";
import { fetchFleetMessaging } from "./messagingApi";

describe("fleet messaging API", () => {
  it("preserves successful services when another service is unavailable", async () => {
    server.use(
      http.get(`${config.paymentServiceUrl}/api/v1/ops/messaging`, () =>
        HttpResponse.json({ title: "Unavailable", status: 503 }, { status: 503 }),
      ),
    );

    const fleet = await fetchFleetMessaging("test-token");

    expect(fleet).toHaveLength(4);
    expect(fleet.find((item) => item.service === "order")?.snapshot?.pendingDeadLetterCount).toBe(
      1,
    );
    expect(fleet.find((item) => item.service === "payment")?.snapshot).toBeNull();
    expect(fleet.find((item) => item.service === "payment")?.error).toBeTruthy();
    expect(fleet.find((item) => item.service === "inventory")?.snapshot?.outboxBacklogCount).toBe(
      0,
    );
  });
});
