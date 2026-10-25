import { test, expect } from '@playwright/test';
import { sampleSource, sampleExercise } from '../fixtures.mjs';
import { challenges } from '../../public/challenges.js';
import { openMode } from './navigation.mjs';

async function openPrivate(page) {
  await page.goto('/');
  await openMode(page, 'private-mode');
  await page.locator('#snippet').fill(sampleSource);
  await page.locator('#api-key').fill('sk-local-test-only');
  await page.locator('#provider-consent').check();
}

async function reviewRound(page, option, line = 3) {
  await page.locator('.ai-options input').nth(option).check();
  await page.locator(`.evidence-line input[value="${line}"]`).check();
  await page.getByRole('button', { name: 'Compare with AI reference' }).click();
}

test('all five curated modes grade, highlight evidence, retain answers and count unique solves', async ({ page }) => {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/#library');
  await expect(page.locator('#check-answer')).toBeDisabled();
  await page.locator('#answer-options input').nth(1).check();
  await page.locator('#check-answer').click();
  await expect(page.locator('#feedback')).toContainText('Not quite');
  await expect(page.locator('#solved-count')).toHaveText('0 / 5');
  const correct = [0, 2, 1, 2, 1];
  for (let i = 0; i < correct.length; i++) {
    await page.locator('.challenge-item').nth(i).click();
    await expect(page.locator('#challenge-title')).toHaveText(challenges[i].title);
    await page.locator('#answer-options input').nth(correct[i]).check();
    await page.locator('#check-answer').click();
    await expect(page.locator('#feedback')).toContainText('Exactly.');
    await expect(page.locator('.code-line.highlight')).toHaveCount(challenges[i].questions[0].lines.length);
  }
  await expect(page.locator('#solved-count')).toHaveText('5 / 5');
  await page.locator('#next-challenge').click();
  await expect(page.locator('#answer-options input').nth(0)).toBeChecked();
  await page.locator('#check-answer').click();
  await expect(page.locator('#solved-count')).toHaveText('5 / 5');
  expect(errors).toEqual([]);
});

test('local worksheet needs no key, uploads text, rejects unsafe files and clears all fields', async ({ page }) => {
  const requests = [];
  page.on('request', req => { if (req.method() === 'POST' && /\/api\/(exercise|coach|attempts)$/.test(req.url())) requests.push(req.url()); });
  await page.goto('/');
  await openMode(page, 'private-mode');
  await page.locator('#worksheet-button').click();
  await expect(page.locator('#ai-status')).toContainText('Add a plain-text');
  await page.locator('#source-file').setInputFiles({ name: 'Hello.java', mimeType: 'text/plain', buffer: Buffer.from('class Hello {}') });
  await expect(page.locator('#snippet')).toHaveValue('class Hello {}');
  await page.locator('#worksheet-button').click();
  await expect(page.locator('#worksheet')).toBeVisible();
  await page.locator('#reflection-input').fill('A local reflection');
  await page.locator('#source-file').setInputFiles({ name: 'bad.exe', mimeType: 'application/octet-stream', buffer: Buffer.from('not source') });
  await expect(page.locator('#upload-error')).toContainText('supported plain-text');
  await page.locator('#source-file').setInputFiles({ name: 'large.txt', mimeType: 'text/plain', buffer: Buffer.alloc(12001, 'a') });
  await expect(page.locator('#upload-error')).toContainText('exceeds 12 KB');
  await page.locator('#source-file').setInputFiles({ name: 'binary.txt', mimeType: 'text/plain', buffer: Buffer.from([0, 1, 2]) });
  await expect(page.locator('#upload-error')).toContainText('Binary content');
  await page.locator('#source-file').setInputFiles({ name: 'bad.txt', mimeType: 'text/plain', buffer: Buffer.from([0xff]) });
  await expect(page.locator('#upload-error')).toContainText('UTF-8');
  await page.locator('#api-key').fill('sk-local-test-only');
  await page.locator('#provider-consent').check();
  await page.locator('#clear-private').click();
  await expect(page.locator('#snippet')).toHaveValue('');
  await expect(page.locator('#api-key')).toHaveValue('');
  await expect(page.locator('#provider-consent')).not.toBeChecked();
  await expect(page.locator('#reflection-input')).toHaveValue('');
  await expect(page.locator('#worksheet')).toBeHidden();
  expect(requests).toEqual([]);
});

test('AI consent, mock generation, content escaping, feedback and zero key persistence', async ({ page }) => {
  let calls = 0;
  const payload = '<img src=x onerror="globalThis.pwned=true">';
  const source = '<script>globalThis.pwned=true</script>';
  await page.route('**/api/exercise', async route => {
    calls++;
    const body = route.request().postDataJSON();
    expect(body.consent).toBe(true);
    expect(body.source).toContain('<script>');
    expect(body.key).toBe('sk-local-test-only');
    const data = sampleExercise();
    data.title = payload; data.assumptions = [payload];
    data.questions.forEach((q, i) => {
      Object.assign(q, {
        prompt: `${payload} ${i + 1}`, options: [payload, 'B', 'C', 'D'], correctIndex: 0,
        explanation: payload, evidence: [{ line: 1, quote: source }], verification: payload
      });
    });
    await route.fulfill({ json: data });
  });
  await page.goto('/');
  await openMode(page, 'private-mode');
  await page.locator('#snippet').fill(source);
  await page.locator('#generate-button').click();
  await expect(page.locator('#ai-status')).toContainText('Add your OpenAI key');
  await page.locator('#api-key').fill('sk-local-test-only');
  await page.locator('#generate-button').click();
  await expect(page.locator('#ai-status')).toContainText('Explicit provider consent');
  expect(calls).toBe(0);
  await page.locator('#provider-consent').check();
  await page.locator('#generate-button').click();
  await expect(page.locator('#ai-title')).toHaveText(payload);
  await expect(page.locator('.ai-question')).toHaveCount(1);
  await expect(page.locator('.round-progress')).toContainText('Round 1 of 3');
  await reviewRound(page, 0, 1);
  await expect(page.locator('.ai-feedback')).toContainText('matches the AI reference');
  await expect(page.locator('.ai-feedback')).toContainText('not verified');
  expect(await page.locator('#ai-result img, #ai-result script').count()).toBe(0);
  expect(await page.evaluate(() => globalThis.pwned)).toBeUndefined();
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length, cookies: document.cookie }))).toEqual({ local: 0, session: 0, cookies: '' });
  await page.reload();
  await openMode(page, 'private-mode');
  await expect(page.locator('#api-key')).toHaveValue('');
  await expect(page.locator('#provider-consent')).not.toBeChecked();
  await expect(page.locator('#ai-result')).toBeHidden();
  expect(calls).toBe(1);
});

test('evidence rounds require citations, retain reasoning, support disputes and never submit answers', async ({ page }) => {
  let calls = 0;
  await page.route('**/api/exercise', route => {
    calls++;
    expect(Object.keys(route.request().postDataJSON()).sort()).toEqual(['consent', 'key', 'source']);
    return route.fulfill({ json: sampleExercise() });
  });
  await openPrivate(page);
  await page.locator('#generate-button').click();
  await expect(page.locator('#ai-title')).toHaveText('The skipped final price');
  await expect(page.locator('#ai-result .assumptions')).toContainText('finite array of numbers');
  const compare = page.getByRole('button', { name: 'Compare with AI reference' });
  await expect(compare).toBeDisabled();
  await page.locator('.ai-options input').first().check();
  await expect(compare).toBeDisabled();
  await page.locator('.evidence-line input[value="3"]').focus();
  await page.keyboard.press('Space');
  await expect(compare).toBeEnabled();
  await page.locator('#ai-reasoning').fill('The loop never starts for one item.');
  await compare.click();
  await expect(page.locator('.ai-feedback')).toContainText('Your evidence: L3');
  await expect(page.locator('.citation-list')).toContainText('prices.length - 1');
  await expect(page.locator('.ai-feedback')).toContainText('Suggested check: not executed');
  await expect(page.locator('.evidence-line.highlight')).toHaveCount(2);
  await page.locator('#ai-dispute').click();
  await expect(page.locator('.round-progress')).toContainText('1 flagged');
  await expect(page.locator('#ai-dispute')).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Next round', exact: true }).click();
  await expect(page.locator('.round-progress')).toContainText('Challenge an assumption');
  await reviewRound(page, 2, 4);
  await page.getByRole('button', { name: 'Next round', exact: true }).click();
  await expect(page.locator('.round-progress')).toContainText('Choose a regression test');
  await reviewRound(page, 0);
  await expect(page.locator('.ai-feedback')).toContainText('Your choice differs');
  await expect(page.locator('.round-complete')).toContainText('not a verified score');
  await expect(page.locator('.round-progress')).toContainText('3/3 reviewed · 1 flagged');
  await expect(page.locator('#solved-count')).toHaveText('0 / 5');
  await page.getByRole('button', { name: 'Previous round', exact: true }).click();
  await expect(page.locator('.ai-options input').nth(2)).toBeChecked();
  await expect(page.locator('.evidence-line input[value="4"]')).toBeChecked();
  await page.getByRole('button', { name: 'Previous round', exact: true }).click();
  await expect(page.locator('#ai-reasoning')).toHaveValue('The loop never starts for one item.');
  await expect(page.locator('#ai-dispute')).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Retry this round' }).click();
  await expect(compare).toBeDisabled();
  await expect(page.locator('.round-progress')).toContainText('2/3 reviewed · 0 flagged');
  await expect(page.locator('#ai-reasoning')).toHaveValue('');
  await expect(page.locator('.evidence-line input:checked')).toHaveCount(0);
  expect(calls).toBe(1);
});

test('evidence selection is bounded to three lines without revealing the AI answer', async ({ page }) => {
  await page.route('**/api/exercise', route => route.fulfill({ json: sampleExercise() }));
  await openPrivate(page);
  await page.locator('#generate-button').click();
  for (const line of [1, 2, 3]) await page.locator(`.evidence-line input[value="${line}"]`).check();
  await page.locator('.evidence-line input[value="4"]').click();
  await expect(page.locator('.evidence-line input[value="4"]')).not.toBeChecked();
  await expect(page.locator('.evidence-count')).toContainText('Deselect one');
  await expect(page.locator('.evidence-line input:checked')).toHaveCount(3);
  await expect(page.locator('.ai-feedback')).toHaveCount(0);
  await expect(page.locator('.evidence-line.highlight')).toHaveCount(0);
});

test('editing and replacing source invalidates consent and clears old AI references', async ({ page }) => {
  let calls = 0;
  await page.route('**/api/exercise', route => { calls++; return route.fulfill({ json: sampleExercise() }); });
  await openPrivate(page);
  await page.locator('#generate-button').click();
  await expect(page.locator('#ai-result')).toBeVisible();
  await page.locator('#snippet').fill('const changed = true;');
  await expect(page.locator('#provider-consent')).not.toBeChecked();
  await expect(page.locator('#ai-result')).toBeHidden();
  await expect(page.locator('#ai-status')).toContainText('Snippet changed');
  await page.locator('#generate-button').click();
  await expect(page.locator('#ai-status')).toContainText('Explicit provider consent');
  await page.locator('#provider-consent').check();
  await page.locator('#source-file').setInputFiles({ name: 'Other.js', mimeType: 'text/plain', buffer: Buffer.from('const replacement = 1;\r\nconsole.log(replacement);') });
  await expect(page.locator('#provider-consent')).not.toBeChecked();
  await expect(page.locator('#snippet')).toHaveValue('const replacement = 1;\nconsole.log(replacement);');
  await expect(page.locator('#ai-status')).toContainText('consent was reset');
  expect(calls).toBe(1);
});

test('client refuses unanchored citations even from a successful local response', async ({ page }) => {
  const data = sampleExercise(); data.questions[0].evidence[0].quote = 'invented code';
  await page.route('**/api/exercise', route => route.fulfill({ json: data }));
  await openPrivate(page);
  await page.locator('#generate-button').click();
  await expect(page.locator('#ai-status')).toContainText('do not match this snippet');
  await expect(page.locator('#ai-result')).toBeHidden();
  await expect(page.locator('#generate-button')).toBeEnabled();
});

test('too many source lines is rejected locally before any provider request', async ({ page }) => {
  let calls = 0;
  await page.route('**/api/exercise', route => { calls++; return route.fulfill({ json: sampleExercise() }); });
  await openPrivate(page);
  await page.locator('#snippet').fill('x\n'.repeat(400));
  await page.locator('#provider-consent').check();
  await page.locator('#generate-button').click();
  await expect(page.locator('#ai-status')).toContainText('no more than 400 source lines');
  await page.locator('#worksheet-button').click();
  await expect(page.locator('#worksheet')).toBeHidden();
  expect(calls).toBe(0);
});

test('provider errors stay visible and generation button recovers', async ({ page }) => {
  await page.route('**/api/exercise', route => route.fulfill({ status: 429, json: { error: 'OpenAI quota or rate limit reached. Check billing or retry later.' } }));
  await page.goto('/');
  await openMode(page, 'private-mode');
  await page.locator('#snippet').fill('class Test {}');
  await page.locator('#api-key').fill('sk-local-test-only');
  await page.locator('#provider-consent').check();
  await page.locator('#generate-button').click();
  await expect(page.locator('#ai-status')).toContainText('quota');
  await expect(page.locator('#generate-button')).toBeEnabled();
  await expect(page.locator('#ai-result')).toBeHidden();
  await page.locator('#forget-key').click();
  await expect(page.locator('#api-key')).toHaveValue('');
  await expect(page.locator('#provider-consent')).not.toBeChecked();
});

test('clear during generation cancels the request and prevents stale results', async ({ page }) => {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  await page.route('**/api/exercise', async route => {
    await gate;
    await route.fulfill({ json: { title: 'Stale', questions: [] } }).catch(() => {});
  });
  await page.goto('/');
  await openMode(page, 'private-mode');
  await page.locator('#snippet').fill('class Test {}');
  await page.locator('#api-key').fill('sk-local-test-only');
  await page.locator('#provider-consent').check();
  await page.locator('#generate-button').click();
  await expect(page.locator('#generate-button')).toBeDisabled();
  await page.locator('#clear-private').click();
  release();
  await expect(page.locator('#generate-button')).toBeEnabled();
  await expect(page.locator('#ai-result')).toBeHidden();
  await expect(page.locator('#ai-status')).toContainText('cleared');
});

for (const action of ['edit', 'forget', 'key-change', 'upload']) {
  test(`${action} during generation cancels the browser request and prevents stale results`, async ({ page }) => {
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    await page.route('**/api/exercise', async route => {
      await gate;
      await route.fulfill({ json: sampleExercise() }).catch(() => {});
    });
    await openPrivate(page);
    await page.locator('#generate-button').click();
    await expect(page.locator('#generate-button')).toBeDisabled();
    const messages = {
      edit: 'Snippet changed', forget: 'Key and consent cleared',
      'key-change': 'Key changed', upload: 'consent was reset'
    };
    if (action === 'edit') await page.locator('#snippet').fill('const nextSource = 1;');
    else if (action === 'forget') await page.locator('#forget-key').click();
    else if (action === 'key-change') await page.locator('#api-key').fill('sk-different-test-key');
    else await page.locator('#source-file').setInputFiles({ name: 'New.js', mimeType: 'text/plain', buffer: Buffer.from('const replacement = 2;') });
    await expect(page.locator('#ai-status')).toContainText(messages[action]);
    release();
    await expect(page.locator('#generate-button')).toBeEnabled();
    await expect(page.locator('#provider-consent')).not.toBeChecked();
    await expect(page.locator('#ai-result')).toBeHidden();
    await expect(page.locator('#ai-status')).toContainText(messages[action]);
  });
}

test('maximum-length unbroken AI text wraps on mobile and empty assumptions stay explicit', async ({ page }) => {
  const data = sampleExercise();
  data.title = 'T'.repeat(120); data.assumptions = [];
  Object.assign(data.questions[0], {
    prompt: 'P'.repeat(1000), explanation: 'E'.repeat(2400), verification: 'V'.repeat(1200),
    options: ['O'.repeat(700), 'B', 'C', 'D']
  });
  await page.setViewportSize({ width: 390, height: 900 });
  await page.route('**/api/exercise', route => route.fulfill({ json: data }));
  await openPrivate(page);
  await page.locator('#generate-button').click();
  await expect(page.locator('#ai-title')).toHaveText(data.title);
  await expect(page.locator('#ai-result .assumptions')).toContainText('declared no assumptions');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await reviewRound(page, 0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

for (const width of [1440, 390]) {
  test(`keyboard and responsive layout at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await page.keyboard.press('Tab');
    await expect(page.getByRole('link', { name: 'Skip to practice' })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.locator('#catalog-title')).toBeFocused();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (!await page.locator('#privacy-toggle').isVisible()) await page.locator('#contents-toggle').click();
    await page.locator('#privacy-toggle').click();
    await expect(page.locator('#privacy')).toBeVisible();
    await expect(page.locator('#privacy-toggle')).toHaveAttribute('aria-expanded', 'true');
    await openMode(page, 'private-mode');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(page.locator('#snippet')).toBeVisible();
  });

  test(`evidence workbench remains usable at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.route('**/api/exercise', route => route.fulfill({ json: sampleExercise() }));
    await openPrivate(page);
    await page.locator('#generate-button').click();
    await expect(page.locator('#ai-title')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await reviewRound(page, 0);
    await expect(page.locator('#ai-feedback')).toBeFocused();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(page.locator('.citation-list')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Next round', exact: true })).toBeVisible();
  });
}
