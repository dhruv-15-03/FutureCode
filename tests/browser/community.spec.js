import { test, expect } from '@playwright/test';
import { openMode } from './navigation.mjs';
import { sampleSource, sampleExercise } from '../fixtures.mjs';

test('saved entries, votes, discussion, private topic progress and opt-in reference board work end to end', async ({ page }) => {
  await page.goto('/#library/alias');
  await page.locator('#answer-options input').first().check();
  await page.locator('#check-answer').click();
  await expect(page.locator('#feedback')).toContainText('Saved to your account');
  const tools = page.locator('#drill-community');
  await tools.locator('summary').first().click();
  await tools.getByRole('button', { name: 'Save entry', exact: true }).click();
  await expect(tools).toContainText('Saved privately');
  await tools.getByRole('button', { name: 'Useful problem', exact: true }).click();
  await expect(tools.getByRole('button', { name: 'Useful problem', exact: true })).toHaveAttribute('aria-pressed', 'true');
  const text = '<img src=x onerror="globalThis.executed=true"> Read the alias before assuming a copy.';
  await tools.getByRole('textbox', { name: 'Your reasoning or question', exact: true }).fill(text);
  await tools.getByRole('button', { name: 'Post discussion', exact: true }).click();
  await expect(tools.locator('.discussion-text')).toContainText(text);
  expect(await page.evaluate(() => globalThis.executed)).toBeUndefined();
  await tools.getByText('Edit your comment', { exact: true }).click();
  await tools.getByRole('textbox', { name: 'Edit comment text', exact: true }).fill('Both references point at the same list.');
  await tools.getByRole('button', { name: 'Save comment revision', exact: true }).click();
  await expect(tools.locator('.discussion-text')).toHaveText('Both references point at the same list.');
  await openMode(page, 'bookmarks-mode');
  await expect(page.locator('#bookmarks-content')).toContainText('The list that changed twice');
  await openMode(page, 'progress-mode');
  await expect(page.locator('#progress-content')).toContainText('References and mutation');
  await openMode(page, 'account-mode');
  await page.locator('#leaderboard-opt-in').check();
  await page.getByRole('button', { name: 'Save board preference', exact: true }).click();
  await expect(page.locator('#account-content')).toContainText('Public participation enabled');
  await openMode(page, 'leaderboard-mode');
  await expect(page.locator('#leaderboard-content tbody')).toContainText('Test reader');
  await expect(page.locator('#leaderboard-content')).not.toContainText('fixture@example.test');
});

test('discussion spoilers stay hidden until an attempt and keys/consent clear on provider changes', async ({ page }) => {
  await page.goto('/#library/alias');
  await page.locator('#drill-community summary').first().click();
  await expect(page.locator('#drill-community')).toContainText('Submit an attempt');
  await expect(page.locator('#drill-community').getByRole('textbox', { name: 'Your reasoning or question', exact: true })).toHaveCount(0);
  await openMode(page, 'private-mode');
  await page.locator('#snippet').fill(sampleSource);
  await page.locator('#api-key').fill('synthetic-openai-key');
  await page.locator('#provider-consent').check();
  await page.locator('#ai-provider').selectOption('gemini');
  await expect(page.locator('#api-key')).toHaveValue('');
  await expect(page.locator('#provider-consent')).not.toBeChecked();
  await expect(page.locator('#private-provider-name')).toHaveText('Google Gemini');
  let sent;
  await page.route('**/api/exercise', async route => { sent = route.request().postDataJSON(); await route.fulfill({ json: sampleExercise() }); });
  await page.locator('#api-key').fill('synthetic-gemini-key');
  await page.locator('#provider-consent').check();
  await page.locator('#generate-button').click();
  await expect(page.locator('#ai-result')).toBeVisible();
  expect(sent.provider).toBe('gemini');
  await openMode(page, 'progress-mode');
  await openMode(page, 'private-mode');
  await expect(page.locator('#api-key')).toHaveValue('');
  await expect(page.locator('#provider-consent')).not.toBeChecked();
});

test('execution and payments fail closed while unverified AI patch advice requires separate consent', async ({ page }) => {
  await page.goto('/#execution');
  await expect(page.locator('#execution-content')).toContainText('Code execution is disabled');
  await expect(page.getByRole('button', { name: 'Submit to external runner', exact: true })).toBeDisabled();
  await page.locator('#run-kind').selectOption('counter-regression');
  await page.locator('#run-source').fill('candidate text');
  await page.locator('.patch-advisory summary').click();
  await page.locator('#patch-reasoning').fill('An atomic update should preserve every completed request.');
  await page.locator('#patch-review-key').fill('synthetic-review-key');
  await page.getByRole('button', { name: 'Request patch advice', exact: true }).click();
  await expect(page.locator('.patch-advisory')).toContainText('Separate patch-review consent');
  let sent;
  await page.route('**/api/patch-review', async route => {
    sent = route.request().postDataJSON();
    await route.fulfill({ json: {
      review: { summary: 'This is advice, not proof.', assumptions: ['One shared counter.'], risks: ['A public harness is not exhaustive.'],
        suggestedChecks: ['Force a contended update.'], citations: [{ file: 'CandidatePatch.java', line: 1, quote: 'candidate text' }] },
      verification: 'ai-advisory-unverified', execution: null, notice: 'No correctness score was assigned.'
    } });
  });
  await page.locator('#patch-review-consent').check();
  await page.getByRole('button', { name: 'Request patch advice', exact: true }).click();
  await expect(page.locator('.advisory-output')).toContainText('Unverified AI review');
  expect(sent.patch).toBe('candidate text'); expect(sent.key).toBe('synthetic-review-key');
  await page.locator('#run-source').fill('edited patch');
  await expect(page.locator('.advisory-output')).toBeEmpty();
  await expect(page.locator('#patch-review-consent')).not.toBeChecked();
  await page.locator('#patch-review-consent').check();
  await page.getByRole('button', { name: 'Request patch advice', exact: true }).click();
  await expect(page.locator('.patch-advisory')).toContainText('cited source that does not match');
  await expect(page.locator('.advisory-output')).toBeEmpty();
  await openMode(page, 'account-mode');
  await page.locator('.optional-payments summary').click();
  await expect(page.locator('.optional-payments')).toContainText('Payments are disabled');
  await openMode(page, 'execution-mode');
  await expect(page.locator('#patch-review-key')).toHaveValue('');
});

test('synthetic external-run UI submits once, polls explicitly and renders result text without running it', async ({ page }) => {
  const id = '12345678-1234-4234-8234-123456789012';
  let submitted = false, polled = false, submits = 0, polls = 0, request;
  const row = () => ({
    id, kind: 'snippet', language: 'java', state: polled ? 'completed' : 'queued', sourceHash: 'a'.repeat(64),
    createdAt: '2026-10-25T12:00:00.000Z',
    result: polled ? { status: 'Accepted', passedExpectedOutput: true, notice: 'Synthetic browser contract only, not real execution.',
      stdout: '<script>globalThis.runOutputExecuted=true</script>', stderr: '', compileOutput: '' } : null
  });
  await page.route('**/api/execution/config', route => route.fulfill({ json: { enabled: true, notice: 'Synthetic runner UI contract only.' } }));
  await page.route('**/api/runs', async route => {
    if (route.request().method() === 'POST') {
      request = route.request().postDataJSON(); submitted = true; submits++;
      await route.fulfill({ status: 201, json: { run: row() } });
    } else await route.fulfill({ json: { items: submitted ? [row()] : [] } });
  });
  await page.route(`**/api/runs/${id}/poll`, async route => { polled = true; polls++; await route.fulfill({ json: { run: row() } }); });
  await page.goto('/#execution');
  await page.locator('#run-source').fill('class Main { public static void main(String[] args) {} }');
  await page.locator('#runner-key').fill('synthetic-rapidapi-key');
  await page.locator('#runner-consent').check();
  await page.getByRole('button', { name: 'Submit to external runner', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Check external result', exact: true })).toBeVisible();
  expect(submits).toBe(1); expect(polls).toBe(0);
  expect(request).toMatchObject({ kind: 'snippet', language: 'java', key: 'synthetic-rapidapi-key', consent: true });
  await page.getByRole('button', { name: 'Check external result', exact: true }).click();
  await expect(page.locator('.execution-history')).toContainText('Synthetic browser contract only');
  await expect(page.locator('.execution-history pre')).toHaveText('<script>globalThis.runOutputExecuted=true</script>');
  expect(polls).toBe(1); expect(await page.evaluate(() => globalThis.runOutputExecuted)).toBeUndefined();
  await page.getByRole('button', { name: 'Forget external keys', exact: true }).click();
  await expect(page.locator('#runner-key')).toHaveValue('');
  await expect(page.locator('#runner-consent')).not.toBeChecked();
});

test('moderators can hide and restore discussion and inspect metadata-only audit events', async ({ page }) => {
  await page.setExtraHTTPHeaders({ 'X-Test-Role': 'admin' });
  await page.goto('/#library/alias');
  const tools = page.locator('#drill-community'), text = `Moderation fixture ${Date.now()}`;
  await tools.locator('summary').first().click();
  await tools.getByRole('textbox', { name: 'Your reasoning or question', exact: true }).fill(text);
  await tools.getByRole('button', { name: 'Post discussion', exact: true }).click();
  let article = tools.locator('.discussion-entry').filter({ hasText: text });
  await article.getByText('Moderate this comment', { exact: true }).click();
  await article.getByRole('textbox', { name: 'Moderator explanation', exact: true }).fill('Temporary visibility check.');
  await article.getByRole('button', { name: 'Hide comment', exact: true }).click();
  await expect(article).toContainText('hidden by moderator');
  await openMode(page, 'review-mode');
  await page.locator('.moderation-audit summary').click();
  await expect(page.locator('.moderation-audit')).toContainText('discussion.hidden');
  await expect(page.locator('.moderation-audit')).not.toContainText(text);
  await openMode(page, 'curated-mode');
  await tools.locator('summary').first().click();
  article = tools.locator('.discussion-entry').filter({ hasText: text });
  await article.getByText('Moderate this comment', { exact: true }).click();
  await article.getByRole('button', { name: 'Restore comment', exact: true }).click();
  await expect(article).not.toContainText('hidden by moderator');
});

for (const width of [320, 390, 768, 1440]) {
  test(`compact chrome stays stable across routes and mobile account actions remain reachable at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await expect(page.locator('#catalog-rows tr[data-provenance="curated"]')).toHaveCount(6);
    const expectedHeader = width <= 760 ? 64 : 80, expectedFooter = width <= 760 ? 208 : 128;
    for (const mode of ['catalog-mode', 'account-mode', 'progress-mode', 'bookmarks-mode', 'execution-mode']) {
      await openMode(page, mode);
      await expect(page.locator('.book-header')).toHaveCSS('min-height', `${expectedHeader}px`);
      const geometry = await page.evaluate(() => ({
        header: document.querySelector('.book-header').getBoundingClientRect().height,
        footer: document.querySelector('.site-footer').getBoundingClientRect().height,
        overflow: document.documentElement.scrollWidth > innerWidth
      }));
      expect(geometry.header).toBeCloseTo(expectedHeader, 0); expect(geometry.footer).toBeCloseTo(expectedFooter, 0); expect(geometry.overflow).toBe(false);
    }
    if (width <= 760) {
      await expect(page.locator('#contents-toggle svg')).toBeVisible();
      await page.locator('#contents-toggle').click();
      await expect(page.locator('#mobile-account-actions #sign-in-action')).toBeVisible();
      await expect(page.locator('#mobile-account-actions #privacy-toggle')).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(page.locator('#contents-toggle')).toBeFocused();
    }
  });
}

test('responsive navigation reparenting preserves unsaved fields and never duplicates account controls', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/#account');
  await page.locator('#profile-name').fill('Keep my unsaved public name');
  await page.setViewportSize({ width: 390, height: 900 });
  await page.locator('#contents-toggle').click();
  await expect(page.locator('#mobile-account-actions #sign-in-action')).toBeVisible();
  await page.setViewportSize({ width: 1440, height: 900 });
  await expect(page.locator('.book-header #sign-in-action')).toBeVisible();
  await expect(page.locator('#sign-in-action')).toHaveCount(1);
  await expect(page.locator('#profile-name')).toHaveValue('Keep my unsaved public name');
});
