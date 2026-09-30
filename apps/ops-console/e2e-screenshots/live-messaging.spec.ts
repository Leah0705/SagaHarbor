import { test, expect, type Page } from "@playwright/test";

// Run only after the four services and Keycloak are healthy and a valid Inventory
// dead letter has been staged. The local realm contains fictional demo users.
test.skip(!process.env.SAGAHARBOR_STAGED_ORDER_ID, "Requires a staged live event");

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
  await operatorPage.screenshot({ path: "../../docs/screenshots/01-login.png" });
  await operatorPage.getByLabel("Username or email").fill("operator.demo");
  await operatorPage.locator('input[name="password"]').fill("OperatorDemo!123");
  await operatorPage.getByRole("button", { name: "Sign In" }).click();
  await expect(operatorPage.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
  await operatorPage.getByRole("link", { name: "Messaging" }).click();
  await expect(operatorPage.getByText(orderPrefix, { exact: false })).toBeVisible({
    timeout: 30_000,
  });
  await expect(operatorPage.getByRole("button", { name: "Replay" })).toHaveCount(0);
  await operatorPage.screenshot({ path: "../../docs/screenshots/08-messaging-live-operator.png" });
  await operatorContext.close();

  const adminContext = await browser.newContext();
  const adminPage = await adminContext.newPage();
  await login(adminPage, "admin.demo", "AdminDemo!123");
  await adminPage.getByRole("link", { name: "Messaging" }).click();
  const eventRow = adminPage.locator("tbody tr").filter({ hasText: orderPrefix });
  await expect(eventRow).toHaveCount(1, { timeout: 30_000 });
  await eventRow.getByRole("button", { name: "Replay" }).click();
  const dialog = adminPage.getByRole("dialog", { name: "Replay dead letter" });
  await expect(dialog).toBeVisible();
  await adminPage.waitForTimeout(500); // let the modal transition finish for the screenshot
  await adminPage.screenshot({
    path: "../../docs/screenshots/09-messaging-live-admin-confirm.png",
  });
  await dialog.getByRole("button", { name: "Replay" }).click();
  await expect(adminPage.getByText(/REPLAYED/)).toBeVisible();
  await expect(dialog).not.toBeVisible();
  await adminPage.screenshot({
    path: "../../docs/screenshots/10-messaging-live-replay-result.png",
  });
  await adminPage.getByRole("button", { name: "Refresh" }).click();
  await expect(adminPage.getByRole("button", { name: "Refresh" })).toBeEnabled();
  await expect(eventRow).toHaveCount(0);
  await adminPage.screenshot({
    path: "../../docs/screenshots/11-messaging-live-replay-postfix.png",
  });
  await adminContext.close();
});
