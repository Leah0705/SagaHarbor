import { defineConfig, devices } from "@playwright/test";

// A separate, deliberately narrow config: only e2e-screenshots/, run via `npm run
// screenshots`, never part of the normal `npm run e2e` suite.
export default defineConfig({
  testDir: "./e2e-screenshots",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: "http://localhost:5173",
    launchOptions: {
      args: ["--disable-dev-shm-usage", "--host-resolver-rules=MAP host.docker.internal 127.0.0.1"],
    },
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        viewport: { width: 1440, height: 900 },
        colorScheme: "light",
      },
    },
  ],
  webServer: {
    command: "node node_modules/vite/bin/vite.js",
    url: "http://localhost:5173",
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
