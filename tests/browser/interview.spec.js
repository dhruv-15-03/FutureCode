import { test, expect } from '@playwright/test';
import { practiceCase } from '../../public/interview-case.js';
import { sampleCoachReply } from '../fixtures.mjs';
import { openMode } from './navigation.mjs';

async function startCase(page) {
  await page.goto('/#interview');
  await expect(page.locator('#interview-mode')).toHaveAttribute('aria-pressed', 'true');
  await page.locator('#case-start').click();
  await expect(page.locator('#case-stage-title')).toHaveText('Read the flow');
}

async function fillFlow(page) {
  await page.locator('#case-flow').fill('The controller records in finally; the shared counter is read by the reporting service after requests complete.');
  await page.locator('#case-questions').fill('Assume both services receive the same counter instance. Are routes canonical and reports read after completion?');
  await page.locator('#case-line-12').check();
  await page.locator('#case-file-1').click();
  await page.locator('#case-line-16').check();
}

async function reachInvestigation(page) {
  await startCase(page);
  await fillFlow(page);
  await page.locator('#case-next').click();
  await expect(page.locator('#case-stage-title')).toHaveText('Reproduce & challenge');
}

async function reproduceLoss(page) {
  for (const operation of ['A read', 'B read', 'A write', 'B write']) {
    await page.getByRole('button', { name: operation, exact: true }).click();
  }
  await expect(page.locator('.trace-outcome')).toContainText('Final count: 1');
}

async function reachPatch(page, correct = true) {
  await reachInvestigation(page);
  await reproduceLoss(page);
  await page.locator('#case-ai-decision input').nth(correct ? 1 : 0).check();
  await page.locator('#case-critique').fill(correct ?
    'A conditional replacement may return false. Ignoring that result drops a completed increment.' :
    'I accept the claim without independently checking the ignored result.');
  await page.locator('#case-next').click();
  await expect(page.locator('#case-stage-title')).toHaveText('Patch & test');
}

async function fillPatch(page, correct = true) {
  await page.locator('#case-patch').fill(correct ? practiceCase.reference.counterSource : 'This is not Java. <script>globalThis.candidateExecuted=true</script>');
  await page.locator('#case-patch-reasoning').fill('An atomic per-route update preserves increments without one global request lock. Contention and overflow still need review.');
  await page.locator('#case-invariant input').nth(correct ? 0 : 1).check();
  await page.locator('#case-expected').fill(correct ? '2' : '1');
  await page.locator('#case-test-plan').fill('Use a barrier so both requests read 0 before either writes. After both complete, assert count 2. Also check a failed handler and an independent route.');
  await page.locator('#case-verification').fill('Compile and run a controlled test in a trusted environment; review boundaries and integration. I have not executed my patch here.');
}

async function openCoach(page) {
  await page.locator('#case-coach > summary').click();
  await expect(page.locator('#coach-prompt')).toBeVisible();
}

async function prepareCoach(page, key = 'sk-coach-test-only') {
  await openCoach(page);
  await page.locator('#coach-key').fill(key);
  await page.locator('#coach-consent').check();
  await page.locator('#coach-prompt').fill('How should I verify the seeded proposal?');
}

test('complete five-file simulation works without a key and separates written work from curated checks', async ({ page }) => {
  const errors = [], apiCalls = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (request.method() === 'POST' && /\/api\/(exercise|coach|attempts)$/.test(request.url())) apiCalls.push(request.url()); });
  await startCase(page);
  await expect(page.locator('.case-file')).toHaveCount(5);
  await page.locator('#case-next').click();
  await expect(page.locator('#case-status')).toContainText('request-to-report path');
  await expect(page.locator('#case-flow')).toBeFocused();
  await fillFlow(page);
  await page.locator('#case-recap > summary').click();
  await expect(page.locator('#case-recap-content')).toContainText('including handlers that throw');
  await expect(page.locator('#case-flow')).toHaveValue(/shared counter/);
  await page.locator('#case-recap > summary').click();
  await page.locator('#case-next').click();
  for (const operation of ['A read', 'A write', 'B read', 'B write']) {
    await page.getByRole('button', { name: operation, exact: true }).click();
  }
  await expect(page.locator('.trace-outcome')).toContainText('Final count: 2');
  await page.locator('#case-next').click();
  await expect(page.locator('#case-status')).toContainText('loses an increment');
  await page.getByRole('button', { name: 'Reset trace', exact: true }).click();
  await reproduceLoss(page);
  await expect(page.locator('#case-task')).toContainText('not a live model response');
  await page.locator('#case-ai-decision input').nth(1).check();
  await page.locator('#case-critique').fill('The ignored false return from replace can drop an increment after both requests read the same value.');
  await page.locator('#case-hint').click();
  await expect(page.locator('#case-hints')).toContainText('finally block');
  await page.locator('#case-next').click();
  await page.locator('#case-next').click();
  await expect(page.locator('#case-status')).toContainText('Edit the proposed');
  await fillPatch(page);
  await page.locator('#case-next').click();
  await expect(page.locator('#case-stage-title')).toHaveText('Debrief');
  await expect(page.locator('#case-task')).toContainText('4/4 curated checks');
  await expect(page.locator('#case-task')).toContainText('not an interview-readiness score');
  await expect(page.locator('#case-task')).toContainText('seeded proposal only');
  await expect(page.locator('#case-task')).toContainText('1 authored hints');
  await expect(page.locator('#case-task')).toContainText('None of your code was executed');
  await expect(page.locator('.case-rubric dt')).toHaveCount(5);
  await expect(page.locator('#solved-count')).toHaveText('0 / 5');
  expect(apiCalls).toEqual([]);
  expect(errors).toEqual([]);
});

test('wrong decisions get reference feedback and even invalid candidate code is never executed or marked correct', async ({ page }) => {
  await reachPatch(page, false);
  await fillPatch(page, false);
  await page.locator('#case-next').click();
  await expect(page.locator('#case-task')).toContainText('1/4 curated checks');
  await expect(page.locator('.check-review')).toHaveCount(3);
  await expect(page.locator('#case-task')).toContainText('still need human review');
  expect(await page.evaluate(() => globalThis.candidateExecuted)).toBeUndefined();
  expect(await page.locator('#case-task script, #case-task img').count()).toBe(0);
});

test('drafts survive navigation and editing a completed prerequisite invalidates forward access', async ({ page }) => {
  await reachPatch(page);
  await fillPatch(page);
  await page.locator('#case-next').click();
  await page.locator('#case-steps button').first().click();
  await expect(page.locator('#case-flow')).toHaveValue(/shared counter/);
  await expect(page.locator('#case-pins button')).toHaveCount(2);
  await page.locator('#case-flow').fill('');
  await expect(page.locator('#case-steps button').last()).toBeDisabled();
  await page.locator('#case-next').click();
  await expect(page.locator('#case-status')).toContainText('request-to-report path');
  await page.locator('#case-flow').fill('A revised request flow: finally records into the shared counter, then the report reads it.');
  await page.locator('#case-next').click();
  await expect(page.locator('.trace-outcome')).toContainText('Final count: 1');
  await expect(page.locator('#case-ai-decision input').nth(1)).toBeChecked();
  await page.locator('#case-next').click();
  await expect(page.locator('#case-patch')).toHaveValue(practiceCase.reference.counterSource);
  await page.locator('#case-next').click();
  await expect(page.locator('#case-stage-title')).toHaveText('Debrief');
});

test('evidence pins are bounded, source stays read-only, and one-file coverage cannot complete the flow', async ({ page }) => {
  await startCase(page);
  await page.locator('#case-flow').fill('A flow description.');
  await page.locator('#case-questions').fill('An explicit assumption.');
  for (const line of [1, 2, 4, 5, 8, 9]) await page.locator(`#case-line-${line}`).check();
  await expect(page.locator('#case-line-3')).toBeDisabled();
  await page.locator('#case-next').click();
  await expect(page.locator('#case-status')).toContainText('at least two different');
  await page.locator('#case-file-1').click();
  await page.locator('#case-line-16').click();
  await expect(page.locator('#case-line-16')).not.toBeChecked();
  await expect(page.locator('#case-evidence-count')).toContainText('Six-pin limit');
  await page.locator('#case-pins button').first().click();
  await page.locator('#case-line-16').check();
  await expect(page.locator('#case-evidence-count')).toContainText('6/6 lines pinned');
  await page.locator('#case-next').click();
  await expect(page.locator('#case-stage-title')).toHaveText('Reproduce & challenge');
  await expect(page.locator('#case-source')).not.toHaveAttribute('contenteditable', 'true');
});

test('switching source files starts at the new file top without discarding written work or pins', async ({ page }) => {
  await startCase(page);
  await fillFlow(page);
  await page.locator('#case-file-4').click();
  await page.locator('#case-source').evaluate(source => { source.scrollTop = source.scrollHeight; source.scrollLeft = 100; });
  await page.locator('#case-file-0').click();
  expect(await page.locator('#case-source').evaluate(source => ({ top: source.scrollTop, left: source.scrollLeft }))).toEqual({ top: 0, left: 0 });
  await expect(page.locator('#case-flow')).toHaveValue(/shared counter/);
  await expect(page.locator('#case-pins button')).toHaveCount(2);
});

test('live coaching requires separate consent and sends only explicit conversation, never other written artifacts', async ({ page }) => {
  const bodies = [];
  await page.route('**/api/coach', route => {
    bodies.push(route.request().postDataJSON());
    return route.fulfill({ json: sampleCoachReply() });
  });
  await startCase(page);
  await page.locator('#case-flow').fill('LOCAL-ARTIFACT-NOT-AUTOMATICALLY-SHARED');
  await openCoach(page);
  await page.locator('#coach-send').click();
  await expect(page.locator('#coach-status')).toContainText('Write a prompt');
  await page.locator('#coach-prompt').fill('How should I check the shared state?');
  await page.locator('#coach-send').click();
  await expect(page.locator('#coach-status')).toContainText('Add your OpenAI key');
  await page.locator('#coach-key').fill('sk-coach-test-only');
  await page.locator('#coach-send').click();
  await expect(page.locator('#coach-status')).toContainText('Explicit coach consent');
  expect(bodies).toHaveLength(0);
  await page.locator('#coach-consent').check();
  await page.locator('#coach-send').click();
  await expect(page.locator('#coach-status')).toContainText('Coach reply received');
  await expect(page.locator('#coach-transcript')).toContainText('AI collaborator · OpenAI · unverified');
  expect(Object.keys(bodies[0]).sort()).toEqual(['caseId', 'consent', 'key', 'messages']);
  expect(JSON.stringify(bodies[0])).not.toContain('LOCAL-ARTIFACT');
  expect(bodies[0].messages).toHaveLength(1);
  await page.locator('#coach-prompt').fill('What should a controlled test assert?');
  await page.locator('#coach-send').click();
  await expect(page.locator('#coach-request-count')).toHaveText('2/6 request attempts used');
  await expect(page.locator('#coach-send')).toBeEnabled();
  expect(bodies[1].messages.map(message => message.role)).toEqual(['user', 'assistant', 'user']);
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length, cookies: document.cookie }))).toEqual({ local: 0, session: 0, cookies: '' });
});

test('coach errors and fabricated citations are explicit, text is escaped, and failed requests count toward the six-attempt cap', async ({ page }) => {
  let calls = 0;
  const payload = '<img src=x onerror="globalThis.coachPwned=true">';
  await page.route('**/api/coach', route => {
    calls++;
    if (calls === 1 || calls === 6) return route.fulfill({ status: 429, json: { error: 'OpenAI quota or rate limit reached.' } });
    const data = sampleCoachReply();
    if (calls === 2) data.citations[0].quote = 'invented source';
    else { data.reply = payload; data.assumptions = [payload]; }
    return route.fulfill({ json: data });
  });
  await startCase(page);
  await prepareCoach(page);
  for (let index = 1; index <= 6; index++) {
    await page.locator('#coach-prompt').fill(`Prompt ${index}: ${payload}`);
    await page.locator('#coach-send').click();
    await expect(page.locator('#coach-request-count')).toHaveText(`${index}/6 request attempts used`);
    await expect(page.locator('#coach-prompt')).toBeEnabled();
    if (index === 1 || index === 6) await expect(page.locator('#coach-status')).toContainText('quota');
    if (index === 2) await expect(page.locator('#coach-status')).toContainText('does not match');
  }
  await expect(page.locator('#coach-send')).toBeDisabled();
  await expect(page.locator('#coach-transcript')).toContainText('No reply received (failed)');
  expect(calls).toBe(6);
  expect(await page.locator('#coach-transcript img, #coach-transcript script').count()).toBe(0);
  expect(await page.evaluate(() => globalThis.coachPwned)).toBeUndefined();
});

for (const action of ['forget', 'key', 'consent', 'leave', 'restart', 'cancel']) {
  test(`pending coach ${action} cancels safely and cannot load a stale reply`, async ({ page }) => {
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    await page.route('**/api/coach', async route => {
      await gate;
      await route.fulfill({ json: sampleCoachReply() }).catch(() => {});
    });
    await startCase(page);
    await prepareCoach(page);
    await page.locator('#coach-send').click();
    await expect(page.locator('#coach-send')).toBeDisabled();
    if (action === 'forget') await page.locator('#coach-forget').click();
    else if (action === 'key') await page.locator('#coach-key').fill('sk-replaced-coach-key');
    else if (action === 'consent') await page.locator('#coach-consent').uncheck();
    else if (action === 'leave') await openMode(page, 'curated-mode');
    else if (action === 'restart') {
      await page.locator('#case-reset').click();
      await page.locator('#case-confirm-reset').click();
    } else await page.locator('#coach-cancel').click();
    release();
    if (action === 'leave') await openMode(page, 'interview-mode');
    if (action === 'restart') {
      await expect(page.locator('#case-brief')).toBeVisible();
      await page.locator('#case-start').click();
    }
    await expect(page.locator('#coach-send')).toBeEnabled();
    await expect(page.locator('#coach-transcript')).not.toContainText('What happens when');
    if (['forget', 'leave', 'restart'].includes(action)) await expect(page.locator('#coach-key')).toHaveValue('');
    if (action !== 'cancel') await expect(page.locator('#coach-consent')).not.toBeChecked();
  });
}

test('finishing while the coach is pending cancels it and the exported record excludes credentials', async ({ page }) => {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  await page.route('**/api/coach', async route => {
    await gate;
    await route.fulfill({ json: sampleCoachReply() }).catch(() => {});
  });
  await reachPatch(page);
  await fillPatch(page);
  await prepareCoach(page, 'sk-export-credential-never-include');
  await page.locator('#coach-send').click();
  await expect(page.locator('#coach-send')).toBeDisabled();
  await page.locator('#case-next').click();
  release();
  await expect(page.locator('#case-stage-title')).toHaveText('Debrief');
  await expect(page.locator('#coach-key')).toHaveValue('');
  await expect(page.locator('#case-task')).toContainText('seeded proposal only');
  const downloadPromise = page.waitForEvent('download');
  await page.locator('#case-download').click();
  const download = await downloadPromise;
  const stream = await download.createReadStream(), chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  const text = Buffer.concat(chunks).toString('utf8'), record = JSON.parse(text);
  expect(download.suggestedFilename()).toBe('futurecode-missing-requests-v1.json');
  expect(record.curatedChecks.every(check => check.passed)).toBe(true);
  expect(record.requiresHumanReview).toBe(true);
  expect(record.artifacts.patch).toBe(practiceCase.reference.counterSource);
  expect(record.coach.repliesReceived).toBe(0);
  expect(text).not.toContain('sk-export-credential-never-include');
  expect(Object.hasOwn(record, 'key')).toBe(false);
});

test('reload clears the whole attempt and coach key; restart requires an explicit confirmation', async ({ page }) => {
  await startCase(page);
  await page.locator('#case-flow').fill('A draft to preserve until confirmation.');
  await prepareCoach(page);
  await page.locator('#case-reset').click();
  await expect(page.locator('#case-flow')).toHaveValue('A draft to preserve until confirmation.');
  await page.locator('#case-reset').click();
  await expect(page.locator('#case-confirm-reset')).toBeHidden();
  await page.reload();
  await expect(page.locator('#case-brief')).toBeVisible();
  await expect(page.locator('#coach-key')).toHaveValue('');
  await expect(page.locator('#coach-consent')).not.toBeChecked();
  await page.locator('#case-start').click();
  await expect(page.locator('#case-flow')).toHaveValue('');
  await expect(page.locator('#case-pins button')).toHaveCount(0);
});

test('long untrusted coach text remains readable without widening the mobile page', async ({ page }) => {
  const data = sampleCoachReply();
  data.reply = 'R'.repeat(2000);
  data.assumptions = ['A'.repeat(300)];
  await page.setViewportSize({ width: 390, height: 900 });
  await page.route('**/api/coach', route => route.fulfill({ json: data }));
  await startCase(page);
  await prepareCoach(page);
  await page.locator('#coach-send').click();
  await expect(page.locator('#coach-status')).toContainText('Coach reply received');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

for (const width of [1440, 1024, 768, 390]) {
  test(`simulation keyboard flow and responsive layout at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/#interview');
    await page.keyboard.press('Tab');
    await expect(page.getByRole('link', { name: 'Skip to practice' })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.locator('#case-title')).toBeFocused();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.locator('#case-start').click();
    await fillFlow(page);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.locator('#case-next').click();
    await reproduceLoss(page);
    await page.locator('#case-ai-decision input').nth(1).check();
    await page.locator('#case-critique').fill('The false return from replace drops an increment.');
    await page.locator('#case-next').click();
    await fillPatch(page);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.locator('#case-next').click();
    await expect(page.locator('#case-stage-title')).toBeFocused();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });
}
