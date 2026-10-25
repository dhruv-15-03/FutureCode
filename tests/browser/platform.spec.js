import { test, expect } from '@playwright/test';
import { openMode } from './navigation.mjs';
import { practiceCase } from '../../public/interview-case.js';

async function editor(page, title) {
  await page.goto('/#contribute/new');
  await expect(page.locator('#problem-title')).toBeVisible();
  await page.locator('#problem-title').fill(title);
  await page.locator('#problem-statement').fill('Two calls overlap. Which update can disappear? State the assumption that makes the answer observable.');
  await page.locator('#source-name').fill('Counter.java');
  await page.locator('#source-code').fill('int previous = count;\ncount = previous + 1;');
  for (const [index, option] of ['An increment', 'A file', 'A thread object', 'Nothing'].entries()) await page.locator(`#problem-option-${index}`).fill(option);
  await page.locator('input[name="proposed-answer"]').first().check();
  await page.locator('#reference-explanation').fill('Two reads of the same old value can produce only one increment.');
  await page.locator('#reference-solution').fill('Use an atomic per-key update.');
  await page.locator('#reference-verification').fill('Force both reads before either write, then assert the required final count.');
  await page.locator('#citation-line').fill('2');
  await page.getByRole('button', { name: 'Add source citation', exact: true }).click();
  await page.locator('#publication-permission').check();
}

test('FutureCode exposes an honest public catalog, search, and guest-only reading without fake account submission', async ({ page }) => {
  await page.setExtraHTTPHeaders({ 'X-Test-Role': 'guest' });
  await page.goto('/');
  await expect(page).toHaveTitle('FutureCode | Future of Coding Based Interviews');
  await expect(page.locator('.brand')).toContainText('FutureCode');
  await expect(page.locator('#catalog-rows tr[data-provenance="curated"]')).toHaveCount(6);
  await page.locator('#catalog-search').fill('finally');
  await expect(page.locator('#catalog-rows tr')).toHaveCount(1);
  await expect(page.locator('#catalog-rows')).toContainText('Follow the return value');
  await page.locator('#catalog-rows a').click();
  await expect(page.locator('#answer-options input').first()).toBeDisabled();
  await expect(page.locator('#check-answer')).toHaveText('Sign in to submit');
  await page.locator('#check-answer').click();
  await expect(page.locator('#account-view')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Continue with Google' })).toBeVisible();
  await page.goto('/#interview');
  await page.locator('#case-start').click();
  await expect(page.locator('#case-flow')).toBeDisabled();
  await expect(page.locator('.case-file')).toHaveCount(5);
  await page.locator('#case-file-1').click();
  await expect(page.locator('#case-source')).toContainText('counts.getOrDefault');
});

test('Google sign-in button uses the backend-issued Google destination, not an impersonated local login', async ({ page }) => {
  await page.setExtraHTTPHeaders({ 'X-Test-Role': 'guest' });
  let input;
  await page.route('**/api/auth/google', async route => {
    input = route.request().postDataJSON();
    await route.fulfill({ json: { url: 'https://accounts.google.com/futurecode-synthetic-consent' } });
  });
  await page.route('https://accounts.google.com/**', route => route.fulfill({ contentType: 'text/plain', body: 'Synthetic Google consent destination for this browser test.' }));
  await page.goto('/#account');
  await page.getByRole('button', { name: 'Continue with Google' }).click();
  await expect(page).toHaveURL('https://accounts.google.com/futurecode-synthetic-consent');
  expect(input).toEqual({ returnTo: 'account' });
});

test('curated scores persist across reload and private records can be viewed and explicitly deleted', async ({ page }) => {
  await page.goto('/#library/alias');
  await page.locator('#answer-options input').first().check();
  await page.locator('#check-answer').click();
  await expect(page.locator('#feedback')).toContainText('Saved to your account');
  await page.reload();
  await expect(page.locator('#solved-count')).toHaveText('1 / 5');
  await openMode(page, 'attempts-mode');
  await expect(page.locator('#attempts-content')).toContainText('The list that changed twice');
  await expect(page.locator('#attempts-content')).toContainText('1/1 curated checks');
  await page.locator('#attempts-content a').first().click();
  await expect(page.locator('.record-json')).toContainText('correctIndex');
  await page.getByRole('button', { name: 'Back to saved work', exact: true }).click();
  await page.getByRole('button', { name: 'Delete record', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm delete', exact: true }).click();
  await expect(page.locator('#attempts-content')).toContainText('No saved attempts yet');
});

test('account display name updates without exposing Google identity on the public catalog', async ({ page }) => {
  await page.goto('/#account');
  await expect(page.locator('#profile-name')).toBeVisible();
  await page.locator('#profile-name').fill('A public reader');
  await page.getByRole('button', { name: 'Save display name', exact: true }).click();
  await expect(page.locator('#account-summary')).toHaveText('A public reader');
  await expect(page.locator('#platform-status')).toContainText('saved');
  await openMode(page, 'catalog-mode');
  await expect(page.locator('#catalog-rows')).not.toContainText('fixture@example.test');
  await expect(page.locator('#catalog-rows')).not.toContainText('Browser fixture');
});

test('an expired session preserves unsaved text for export instead of pretending a save succeeded', async ({ page }) => {
  await page.goto('/#contribute/new');
  await expect(page.locator('#problem-title')).toBeVisible();
  await page.locator('#problem-title').fill('Keep this unsaved work');
  await page.route('**/api/contributions', route => route.fulfill({ status: 401, json: { error: 'Sign in again.', code: 'authentication_required' } }));
  await page.getByRole('button', { name: 'Save private draft', exact: true }).click();
  await expect(page.locator('#problem-title')).toHaveValue('Keep this unsaved work');
  await expect(page.locator('#editor-status')).toContainText('Sign in again');
  await expect(page.locator('#session-retry')).toBeVisible();
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download this draft', exact: true }).click();
  const download = await downloadPromise, stream = await download.createReadStream(), chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  expect(JSON.parse(Buffer.concat(chunks).toString()).title).toBe('Keep this unsaved work');
});

test('casebook metadata remains inside the mobile viewport and contents has a keyboard escape', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 850 });
  await page.goto('/');
  await expect(page.locator('#catalog-rows tr[data-provenance="curated"]')).toHaveCount(6);
  const hiddenCells = await page.locator('#catalog-rows tr[data-provenance="curated"]').first().locator('td').evaluateAll(cells => cells.some(cell => cell.getBoundingClientRect().right > innerWidth));
  expect(hiddenCells).toBe(false);
  await page.locator('#contents-toggle').click();
  await expect(page.locator('#catalog-mode')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.locator('#contents-toggle')).toBeFocused();
  await expect(page.locator('#contents-toggle')).toHaveAttribute('aria-expanded', 'false');
});

test('a contribution stays private through draft/pending, a moderator publishes it, and students compare with a labeled author reference', async ({ browser, baseURL }) => {
  const student = await browser.newContext({ baseURL }), moderator = await browser.newContext({ baseURL, extraHTTPHeaders: { 'X-Test-Role': 'admin' } }), visitor = await browser.newContext({ baseURL, extraHTTPHeaders: { 'X-Test-Role': 'guest' } });
  const authorPage = await student.newPage(), adminPage = await moderator.newPage(), guestPage = await visitor.newPage();
  const title = `Counter review ${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  try {
    await editor(authorPage, title);
    await authorPage.getByRole('button', { name: 'Save private draft', exact: true }).click();
    await expect(authorPage.locator('#editor-status')).toContainText('not been published');
    const id = authorPage.url().split('/').pop();
    expect(id).toMatch(/^[a-f0-9-]{36}$/);
    await guestPage.goto(`/#problem/${id}`);
    await expect(guestPage.locator('#problem-content')).toContainText('not found');
    await authorPage.getByRole('button', { name: 'Submit for moderator review', exact: true }).click();
    await expect(authorPage.locator('#contribute-content')).toContainText('pending');
    await adminPage.goto('/#review');
    await adminPage.getByRole('link', { name: title, exact: true }).click();
    await expect(adminPage.locator('#review-content')).toContainText('count = previous + 1;');
    await adminPage.getByRole('button', { name: 'Approve and publish', exact: true }).click();
    await expect(adminPage.locator('#review-content .error')).toContainText('Confirm review');
    await adminPage.locator('#review-confirmed').check();
    await adminPage.getByRole('button', { name: 'Approve and publish', exact: true }).click();
    await expect(adminPage).toHaveURL(/#review$/);
    await expect(adminPage.locator('#review-content')).not.toContainText(title);
    await guestPage.getByRole('button', { name: 'Retry this entry', exact: true }).click();
    await expect(guestPage.locator('#problem-content')).toContainText(title);
    await expect(guestPage.locator('#community-explanation')).toBeDisabled();
    await authorPage.goto(`/#problem/${id}`);
    await authorPage.locator('input[name="community-answer"]').first().check();
    await authorPage.getByRole('button', { name: 'Submit and save response', exact: true }).click();
    await expect(authorPage.locator('.community-feedback')).toContainText('Matches the author reference');
    await expect(authorPage.locator('.community-feedback')).toContainText('not a verified correctness result');
    await expect(authorPage.locator('.community-feedback')).toContainText('Use an atomic per-key update');
    await authorPage.locator('#problem-content .community-tools summary').first().click();
    await expect(authorPage.locator('#problem-content').getByRole('button', { name: 'Useful problem', exact: true })).toBeDisabled();
    await expect(authorPage.locator('#problem-content')).toContainText('You cannot vote on your own contribution');
    await openMode(authorPage, 'attempts-mode');
    await expect(authorPage.locator('#attempts-content')).toContainText('1/1 author-reference match');
  } finally { await student.close(); await moderator.close(); await visitor.close(); }
});

test('pending contributions must be withdrawn before editing; rejected drafts remain editable with the moderator note', async ({ page, browser, baseURL }) => {
  const title = `Revision exercise ${Date.now()}`;
  await editor(page, title);
  await page.getByRole('button', { name: 'Submit for moderator review', exact: true }).click();
  await page.getByRole('link', { name: title, exact: true }).click();
  await expect(page.locator('#problem-title')).toBeDisabled();
  await page.getByRole('button', { name: 'Withdraw review to edit', exact: true }).click();
  await expect(page.locator('#problem-title')).toBeEnabled();
  const newTitle = `${title} revised`;
  await page.locator('#problem-title').fill(newTitle);
  await page.getByRole('button', { name: 'Submit for moderator review', exact: true }).click();
  const admin = await browser.newContext({ baseURL, extraHTTPHeaders: { 'X-Test-Role': 'admin' } });
  try {
    const reviewer = await admin.newPage();
    await reviewer.goto('/#review');
    await reviewer.getByRole('link', { name: newTitle, exact: true }).click();
    await reviewer.locator('#review-note').fill('Clarify which requests completed before the report.');
    await reviewer.locator('#review-confirmed').check();
    await reviewer.getByRole('button', { name: 'Return with feedback', exact: true }).click();
    await page.getByRole('link', { name: newTitle, exact: true }).click();
    await expect(page.locator('#contribute-content')).toContainText('Clarify which requests');
    await expect(page.locator('#problem-title')).toBeEnabled();
  } finally { await admin.close(); }
});

test('draft validation preserves user input, source edits clear stale evidence, and written mode has no fabricated choice score', async ({ page }) => {
  await page.goto('/#contribute/new');
  await expect(page.locator('#problem-title')).toBeVisible();
  await page.getByRole('button', { name: 'Save private draft', exact: true }).click();
  await expect(page.locator('#editor-status')).toContainText('3-120');
  await page.locator('#problem-title').fill('My preserved draft');
  await page.locator('#source-code').fill('line one\nline two');
  await page.locator('#citation-line').fill('2');
  await page.getByRole('button', { name: 'Add source citation', exact: true }).click();
  await expect(page.locator('.problem-editor .citation-list')).toContainText('line two');
  await page.locator('#source-code').fill('line one\nchanged line');
  await expect(page.locator('.problem-editor .citation-list li')).toHaveCount(0);
  await expect(page.locator('#editor-status')).toContainText('cleared');
  await page.locator('#problem-response-mode').selectOption('written');
  await expect(page.locator('input[name="proposed-answer"]')).toHaveCount(0);
  await expect(page.locator('.problem-editor')).toContainText('without an automatic correctness score');
  await page.route('**/api/contributions', route => route.fulfill({ status: 503, json: { error: 'Database unavailable. Retry later.', code: 'database_unavailable' } }));
  await page.getByRole('button', { name: 'Save private draft', exact: true }).click();
  await expect(page.locator('#editor-status')).toContainText('Database unavailable');
  await expect(page.locator('#problem-title')).toHaveValue('My preserved draft');
  await expect(page.locator('#source-code')).toHaveValue('line one\nchanged line');
});

test('student accounts cannot open the review desk or forge a moderator-only page', async ({ page }) => {
  await page.goto('/#review');
  await expect(page.locator('#review-content')).toContainText('Moderator access required');
  await expect(page.locator('#review-mode')).toBeHidden();
});

test('explicit sign-out clears private snippet credentials and work without storing provider keys', async ({ page }) => {
  await page.goto('/#private');
  await page.locator('#snippet').fill('private source text');
  await page.locator('#api-key').fill('sk-private-ephemeral-only');
  await page.locator('#provider-consent').check();
  await openMode(page, 'account-mode');
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page.locator('#account-summary')).toHaveText('Guest reader');
  await openMode(page, 'private-mode');
  await expect(page.locator('#snippet')).toHaveValue('');
  await expect(page.locator('#api-key')).toHaveValue('');
  await expect(page.locator('#snippet')).toBeDisabled();
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 });
});

test('case submissions store only bounded written work and server-checked results, not coach keys or chat', async ({ page }) => {
  let saved;
  page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/api/attempts')) saved = request.postDataJSON(); });
  await page.goto('/#interview');
  await page.locator('#case-start').click();
  await page.locator('#case-flow').fill('The controller counts in finally and the report reads shared state.');
  await page.locator('#case-questions').fill('Assume one injected counter.');
  await page.locator('#case-line-12').check();
  await page.locator('#case-file-1').click();
  await page.locator('#case-line-16').check();
  await page.locator('#case-next').click();
  for (const name of ['A read', 'B read', 'A write', 'B write']) await page.getByRole('button', { name, exact: true }).click();
  await page.locator('#case-ai-decision input').nth(1).check();
  await page.locator('#case-critique').fill('Ignoring a failed replace loses an increment.');
  await page.locator('#case-next').click();
  await page.locator('#case-patch').fill(practiceCase.reference.counterSource);
  await page.locator('#case-patch-reasoning').fill('Apply an atomic per-route update.');
  await page.locator('#case-invariant input').first().check();
  await page.locator('#case-expected').fill('2');
  await page.locator('#case-test-plan').fill('Force both reads, join the workers, assert 2; keep failure counting and route isolation.');
  await page.locator('#case-verification').fill('Compile, inspect, and run trusted tests separately.');
  await page.locator('#case-next').click();
  await page.locator('#case-save').click();
  await expect(page.locator('#case-status')).toContainText('Saved 4/4 curated checks');
  expect(Object.keys(saved.payload).sort()).toEqual(['artifacts', 'elapsedSeconds', 'hintsViewed', 'pins', 'schedule', 'selections']);
  expect(saved.payload.key).toBeUndefined();
  expect(saved.payload.coach).toBeUndefined();
  await openMode(page, 'attempts-mode');
  await expect(page.locator('#attempts-content')).toContainText('4/4 curated checks');
});

for (const width of [390, 768, 1440, 1920]) {
  test(`casebook catalog, account and contribution layout at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await expect(page.locator('#catalog-rows tr[data-provenance="curated"]')).toHaveCount(6);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await openMode(page, 'account-mode');
    await expect(page.locator('#profile-name')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await openMode(page, 'contribute-mode');
    await page.getByRole('button', { name: 'Write a problem', exact: true }).click();
    await expect(page.locator('#problem-title')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });
}
