import { test, expect } from "@playwright/test";
import { enterConsole } from "../e2e/helpers/auth";

// Captures the six labeled demo-mode screens in docs/screenshots/. Run with
// `npm run screenshots`; the login and live messaging captures are separate.
test.beforeEach(async ({ page }) => {
  await enterConsole(page);
});

test("capture Overview", async ({ page }) => {
  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
  await expect(page.getByText("Orders received")).toBeVisible();
  await page.waitForTimeout(500);
  await page.screenshot({ path: "../../docs/screenshots/02-overview.png" });
});

test("capture Work Queue", async ({ page }) => {
  await page.getByRole("link", { name: "Work Queue" }).click();
  await expect(page.getByRole("heading", { name: "Work Queue", level: 1 })).toBeVisible();
  await expect(page.locator("tbody tr").first()).toBeVisible();
  await page.screenshot({ path: "../../docs/screenshots/03-work-queue.png" });
});

test("capture Order Detail", async ({ page }) => {
  await page.getByRole("link", { name: "Work Queue" }).click();
  await expect(page.locator("tbody tr").first()).toBeVisible();
  await page.locator("tbody tr").first().getByRole("link").click();
  await expect(page.getByText("Event timeline")).toBeVisible();
  await page.screenshot({ path: "../../docs/screenshots/04-order-detail.png" });
});

test("capture Incidents", async ({ page }) => {
  await page.getByRole("link", { name: "Incidents" }).click();
  await expect(page.getByRole("heading", { name: "Incidents", level: 1 })).toBeVisible();
  await expect(page.locator("tbody tr").first()).toBeVisible();
  await page.screenshot({ path: "../../docs/screenshots/05-incidents.png" });
});

test("capture Inventory Risk", async ({ page }) => {
  await page.getByRole("link", { name: "Inventory Risk" }).click();
  await expect(page.getByRole("heading", { name: "Inventory Risk", level: 1 })).toBeVisible();
  await expect(page.getByText("Low-stock SKUs")).toBeVisible();
  await page.screenshot({ path: "../../docs/screenshots/06-inventory-risk.png" });
});

test("capture Fulfillment Board", async ({ page }) => {
  await page.getByRole("link", { name: "Fulfillment Board" }).click();
  await expect(page.getByRole("heading", { name: "Fulfillment Board", level: 1 })).toBeVisible();
  await page.waitForTimeout(500);
  await page.screenshot({ path: "../../docs/screenshots/07-fulfillment-board.png" });
});
