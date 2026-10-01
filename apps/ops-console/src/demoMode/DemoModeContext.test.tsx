import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { config } from "../config";
import { DemoModeProvider } from "./DemoModeContext";

afterEach(() => vi.restoreAllMocks());

describe("backend connectivity", () => {
  it("keeps live mode when the order service is down but another service is reachable", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).startsWith(config.inventoryServiceUrl)) return new Response(null);
      throw new Error("service unreachable");
    });

    render(<DemoModeProvider>{(state) => <span>{state}</span>}</DemoModeProvider>);

    expect(await screen.findByText("live")).toBeInTheDocument();
  });
});
