import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/browser',
  fullyParallel: true,
  reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:4318', browserName: 'chromium', headless: true },
  webServer: {
    command: process.platform === 'win32'
      ? 'powershell -NoProfile -File scripts\\browser-server.ps1'
      : './mvnw -B -q test-compile dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=target/test-classpath.txt && java -Xms32m -Xmx160m -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" io.futurecode.FixtureApplication',
    url: 'http://127.0.0.1:4318',
    reuseExistingServer: false,
    timeout: 180_000
  }
});
