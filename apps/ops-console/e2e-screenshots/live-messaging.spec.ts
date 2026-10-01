import { test, expect, type Page } from "@playwright/test";

// Run only after the four services and Keycloak are healthy and a valid Inventory
// dead letter has been staged. The local realm contains fictional demo users.
test.skip(!process.env.SAGAHARBOR_STAGED_ORDER_ID, "Requires a staged live event");
const screenshotDir = process.env.SAGAHARBOR_SCREENSHOT_DIR ?? "../../docs/screenshots";
const screenshotPath = (name: string) => `${screenshotDir}/${name}`;

async function login(page: Page, username: string, password: string) {
  await page.goto("/");
  await expect(page.getByLabel("Username or email")).toBeVisible();
  await page.getByLabel("Username or email").fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.getByRole("button", { name: "Sign In" }).click();
  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
}

test("capture live operator and admin replay", async ({ browser }) => {
  test.setTimeout(120_000);
  const orderPrefix = process.env.SAGAHARBOR_STAGED_ORDER_ID!.slice(0, 8);

  const operatorContext = await browser.newContext();
  const operatorPage = await operatorContext.newPage();
  await operatorPage.goto("/");
  await expect(operatorPage.getByLabel("Username or email")).toBeVisible();
  await operatorPage.screenshot({ path: screenshotPath("01-login.png") });
  await operatorPage.getByLabel("Username or email").fill("operator.demo");
  await operatorPage.locator('input[name="password"]').fill("OperatorDemo!123");
  await operatorPage.getByRole("button", { name: "Sign In" }).click();
  await expect(operatorPage.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
  await operatorPage.getByRole("link", { name: "Messaging" }).click();
  await expect(operatorPage.getByText(orderPrefix, { exact: false })).toBeVisible({
    timeout: 30_000,
  });
  await expect(operatorPage.getByRole("button", { name: "Replay" })).toHaveCount(0);
  await operatorPage.screenshot({
    path: screenshotPath("08-messaging-live-operator.png"),
    fullPage: true,
  });
  await operatorContext.close();

  const adminContext = await browser.newContext();
  const adminPage = await adminContext.newPage();
  await login(adminPage, "admin.demo", "AdminDemo!123");
  await adminPage.getByRole("link", { name: "Messaging" }).click();
  const backlogTable = adminPage.locator("table").first();
  const orderBacklog = backlogTable.locator("tbody tr").filter({ hasText: "Order" });
  const eventRow = adminPage
    .locator("table")
    .nth(1)
    .locator("tbody tr")
    .filter({ hasText: "Inventory" })
    .filter({ hasText: orderPrefix });
  await expect(eventRow).toHaveCount(1, { timeout: 30_000 });
  const orderPendingBefore = Number(await orderBacklog.locator("td").nth(1).textContent());
  await eventRow.getByRole("button", { name: "Replay" }).click();
  const dialog = adminPage.getByRole("dialog", { name: "Replay dead letter" });
  await expect(dialog).toBeVisible();
  await adminPage.waitForTimeout(500); // let the modal transition finish for the screenshot
  await adminPage.screenshot({
    path: screenshotPath("09-messaging-live-admin-confirm.png"),
    fullPage: true,
  });
  await dialog.getByRole("button", { name: "Replay" }).click();
  await expect(adminPage.getByText(/REPLAYED/)).toBeVisible();
  await expect(dialog).not.toBeVisible();
  await expect(adminPage.getByRole("button", { name: "Refresh" })).toBeEnabled();
  const inventoryBacklog = backlogTable.locator("tbody tr").filter({ hasText: "Inventory" });
  await expect(inventoryBacklog.locator("td").nth(1)).toHaveText("0");
  await adminPage.screenshot({
    path: screenshotPath("10-messaging-live-replay-result.png"),
    fullPage: true,
  });
  await adminPage.reload();
  await expect(adminPage.getByRole("button", { name: "Refresh" })).toBeEnabled();
  await expect(inventoryBacklog.locator("td").nth(1)).toHaveText("0");
  await expect(eventRow).toHaveCount(0);
  await expect(adminPage.getByText("Replay submitted")).toHaveCount(0);
  await expect
    .poll(
      async () => {
        await adminPage.getByRole("button", { name: "Refresh" }).click();
        await expect(adminPage.getByRole("button", { name: "Refresh" })).toBeEnabled();
        const orderPending = Number(await orderBacklog.locator("td").nth(1).textContent());
        const outboxCounts = (
          await backlogTable.locator("tbody tr td:nth-child(3)").allTextContents()
        ).map((count) => Number(count.trim()));
        return orderPending > orderPendingBefore && outboxCounts.every((count) => count === 0);
      },
      { timeout: 60_000 },
    )
    .toBe(true);
  await adminPage.screenshot({
    path: screenshotPath("11-messaging-live-replay-postfix.png"),
    fullPage: true,
  });
  await adminContext.close();
});
