import { test, expect } from "@playwright/test";
import { enterConsole } from "../e2e/helpers/auth";

// Set SAGAHARBOR_CAPTURE_LIVE=1 to capture seeded, live backend screens.
// Without it, the existing demo-mode capture remains available.
const live = process.env.SAGAHARBOR_CAPTURE_LIVE === "1";
const screenshotDir = process.env.SAGAHARBOR_SCREENSHOT_DIR ?? "../../docs/screenshots";
const screenshotPath = (name: string) => `${screenshotDir}/${name}`;

test.beforeEach(async ({ page }) => {
  if (!live) {
    await enterConsole(page);
    return;
  }

  await page.goto("/");
  await expect(page.getByLabel("Username or email")).toBeVisible({ timeout: 30_000 });
  await page.getByLabel("Username or email").fill("operator.demo");
  await page.locator('input[name="password"]').fill("OperatorDemo!123");
  await page.getByRole("button", { name: "Sign In" }).click();
  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByTestId("demo-mode-banner")).toHaveCount(0);
});

test("capture Overview", async ({ page }) => {
  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
  await expect(page.getByText("Orders received")).toBeVisible();
  if (!live) {
    await page.getByLabel("From", { exact: true }).fill("2026-07-14");
    await page.getByLabel("To", { exact: true }).fill("2026-07-21");
  }
  await expect(page.getByText("Order throughput")).toBeVisible();
  await page.screenshot({ path: screenshotPath("02-overview.png"), fullPage: true });
});

test("capture Work Queue", async ({ page }) => {
  await page.getByRole("link", { name: "Work Queue" }).click();
  await expect(page.getByRole("heading", { name: "Work Queue", level: 1 })).toBeVisible();
  await expect(page.locator("tbody tr").first()).toBeVisible();
  await page.screenshot({ path: screenshotPath("03-work-queue.png"), fullPage: true });
});

test("capture Order Detail", async ({ page }) => {
  await page.getByRole("link", { name: "Work Queue" }).click();
  await expect(page.locator("tbody tr").first()).toBeVisible();
  await page.locator("tbody tr").first().getByRole("link").click();
  await expect(page.getByText("Event timeline")).toBeVisible();
  await expect(page.getByLabel("Loading fulfillment")).toHaveCount(0);
  await expect(page.getByLabel("Loading timeline")).toHaveCount(0);
  await expect(page.getByLabel("Loading incidents")).toHaveCount(0);
  await expect(
    page.getByText("Event timeline").locator("..").locator("tbody tr").first(),
  ).toBeVisible();
  await page.screenshot({ path: screenshotPath("04-order-detail.png"), fullPage: true });
});

test("capture Incidents", async ({ page }) => {
  await page.getByRole("link", { name: "Incidents" }).click();
  await expect(page.getByRole("heading", { name: "Incidents", level: 1 })).toBeVisible();
  await expect(page.locator("tbody tr").first()).toBeVisible();
  await page.screenshot({ path: screenshotPath("05-incidents.png"), fullPage: true });
});

test("capture Inventory Risk", async ({ page }) => {
  await page.getByRole("link", { name: "Inventory Risk" }).click();
  await expect(page.getByRole("heading", { name: "Inventory Risk", level: 1 })).toBeVisible();
  await expect(page.getByText("Low-stock SKUs")).toBeVisible();
  await expect(
    page.getByText("Low-stock SKUs").locator("..").locator("tbody tr").first(),
  ).toBeVisible();
  if (!live) {
    await page.getByLabel("From", { exact: true }).fill("2026-07-14");
    await page.getByLabel("To", { exact: true }).fill("2026-07-21");
  }
  await page.screenshot({ path: screenshotPath("06-inventory-risk.png"), fullPage: true });
});

test("capture Fulfillment Board", async ({ page }) => {
  await page.getByRole("link", { name: "Fulfillment Board" }).click();
  await expect(page.getByRole("heading", { name: "Fulfillment Board", level: 1 })).toBeVisible();
  for (const stage of ["ASSIGNED", "PICKING", "PACKED", "DISPATCHED"]) {
    await expect(page.getByLabel(`Loading ${stage} fulfillments`)).toHaveCount(0);
  }
  await page.screenshot({ path: screenshotPath("07-fulfillment-board.png"), fullPage: true });
});
