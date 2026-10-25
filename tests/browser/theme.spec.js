import { test, expect } from '@playwright/test';
import { openMode } from './navigation.mjs';

async function assertReadable(page) {
  const audit = await page.evaluate(() => {
    const rgb = value => {
      const parts = value.match(/[\d.]+/g)?.map(Number);
      if (!parts || parts.length < 3) throw new Error(`Unsupported computed color: ${value}`);
      return [...parts.slice(0, 3), parts[3] ?? 1];
    };
    const luminance = color => {
      const linear = color.slice(0, 3).map(value => {
        const channel = value / 255;
        return channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4;
      });
      return linear[0] * .2126 + linear[1] * .7152 + linear[2] * .0722;
    };
    const contrast = (first, second) => {
      const values = [luminance(first), luminance(second)].sort((a, b) => b - a);
      return (values[0] + .05) / (values[1] + .05);
    };
    const backgrounds = new WeakMap();
    const background = element => {
      if (!element) return [255, 255, 255, 1];
      if (backgrounds.has(element)) return backgrounds.get(element);
      const color = rgb(getComputedStyle(element).backgroundColor);
      const under = color[3] === 1 ? color : background(element.parentElement);
      const result = color.slice(0, 3).map((channel, index) => channel * color[3] + under[index] * (1 - color[3]));
      backgrounds.set(element, [...result, 1]);
      return [...result, 1];
    };
    const visible = element => element.getClientRects().length > 0 &&
      !element.closest('[hidden], .visually-hidden, :disabled, script, style') &&
      getComputedStyle(element).visibility === 'visible';
    const failures = [];
    let pairs = 0;
    const check = (element, color, kind, minimum, surface = element) => {
      pairs++;
      const ratio = contrast(rgb(color), background(surface));
      if (ratio < minimum) failures.push({ element: element.id || element.className || element.tagName, kind, ratio, minimum });
    };
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    for (let text = walker.nextNode(); text; text = walker.nextNode()) {
      const element = text.parentElement;
      if (!text.textContent.trim() || !element || !visible(element)) continue;
      const style = getComputedStyle(element);
      const large = parseFloat(style.fontSize) >= 24 || (parseFloat(style.fontSize) >= 18.667 && parseFloat(style.fontWeight) >= 700);
      check(element, style.color, 'text', large ? 3 : 4.5);
    }
    for (const field of document.querySelectorAll('input[placeholder], textarea[placeholder]')) {
      if (visible(field)) check(field, getComputedStyle(field, '::placeholder').color, 'placeholder', 4.5);
    }
    for (const element of document.querySelectorAll('.primary, .secondary, .google-button, .danger-button, .answer-option, .case-file, .case-pin, .file-button, input:not([type=checkbox]):not([type=radio]):not([type=file]), textarea, select')) {
      if (!visible(element)) continue;
      const style = getComputedStyle(element);
      if (parseFloat(style.borderTopWidth) > 0) check(element, style.borderTopColor, 'control edge', 3, element.parentElement);
    }
    return { pairs, failures, overflow: document.documentElement.scrollWidth > innerWidth };
  });
  expect(audit.failures).toEqual([]);
  expect(audit.pairs).toBeGreaterThan(15);
  expect(audit.overflow).toBe(false);
}

async function capture(page, testInfo, name) {
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: testInfo.outputPath(name), fullPage: true });
}

for (const width of [1440, 390]) {
  test(`dark casebook stays readable across work surfaces at ${width}px`, async ({ page }, testInfo) => {
    test.setTimeout(60_000);
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await expect(page.locator('#catalog-rows tr[data-provenance="curated"]')).toHaveCount(6);
    await expect(page.locator('html')).toHaveCSS('color-scheme', 'dark');
    await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(22, 18, 16)');
    await assertReadable(page);
    await capture(page, testInfo, `catalog-${width}.png`);
    await page.locator('#catalog-search').hover();
    await assertReadable(page);
    await openMode(page, 'account-mode');
    await expect(page.locator('#profile-name')).toBeVisible();
    await assertReadable(page);
    await openMode(page, 'curated-mode');
    await page.locator('#answer-options input').first().check();
    await assertReadable(page);
    await page.locator('#check-answer').hover();
    await assertReadable(page);
    await page.locator('#check-answer').click();
    await expect(page.locator('#feedback')).toBeVisible();
    await assertReadable(page);
    await openMode(page, 'private-mode');
    await page.locator('#source-file').setInputFiles({ name: 'not-source.exe', mimeType: 'application/octet-stream', buffer: Buffer.from('not source') });
    await expect(page.locator('#upload-error')).toBeVisible();
    await assertReadable(page);
    await openMode(page, 'contribute-mode');
    await page.getByRole('button', { name: 'Write a problem', exact: true }).click();
    await expect(page.locator('#problem-title')).toBeVisible();
    await page.getByRole('button', { name: 'Save private draft', exact: true }).click();
    await assertReadable(page);
    await capture(page, testInfo, `editor-${width}.png`);
    await openMode(page, 'interview-mode');
    await page.locator('#case-start').click();
    await page.locator('#case-line-12').check();
    await page.locator('#case-file-1').click();
    await page.locator('#case-line-16').check();
    await assertReadable(page);
    await capture(page, testInfo, `source-${width}.png`);
    await page.goto('/privacy');
    await expect(page.locator('h1')).toHaveText('Privacy & data');
    await assertReadable(page);
  });
}

test('guest sign-in, destructive states and keyboard focus keep their contrast', async ({ page }) => {
  await page.setExtraHTTPHeaders({ 'X-Test-Role': 'guest' });
  await page.goto('/#account');
  await expect(page.locator('.google-button')).toBeVisible();
  await page.locator('.google-button').hover();
  await assertReadable(page);
  await page.locator('.google-button').focus();
  await expect(page.locator('.google-button')).toHaveCSS('outline-color', 'rgb(157, 200, 173)');
  await expect(page.locator('.google-button')).toHaveCSS('outline-width', '2px');
  await page.setExtraHTTPHeaders({});
  await page.reload();
  await expect(page.locator('.account-delete summary')).toBeVisible();
  await page.locator('.account-delete summary').click();
  await assertReadable(page);
});

test('the first view adds no external assets or decorative rendering cost', async ({ page }) => {
  const requests = [];
  page.on('request', request => requests.push(request.url()));
  await page.goto('/');
  await expect(page.locator('#catalog-rows tr[data-provenance="curated"]')).toHaveCount(6);
  expect(requests.every(url => new URL(url).origin === new URL(page.url()).origin)).toBe(true);
  const effects = await page.evaluate(() => {
    const elements = [...document.querySelectorAll('body *')].filter(element => element.getClientRects().length);
    return elements.some(element => {
      const style = getComputedStyle(element);
      return style.backgroundImage.includes('gradient') || style.boxShadow !== 'none' ||
        style.backdropFilter !== 'none' || style.animationName !== 'none';
    });
  });
  expect(effects).toBe(false);
});

test('the first catalog load reserves space instead of moving the footer after paint', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.addInitScript(() => {
    globalThis.catalogLayoutShift = 0;
    new PerformanceObserver(list => {
      for (const entry of list.getEntries()) if (!entry.hadRecentInput) globalThis.catalogLayoutShift += entry.value;
    }).observe({ type: 'layout-shift', buffered: true });
  });
  let release;
  const held = new Promise(resolve => { release = resolve; });
  await page.route('**/api/catalog', async route => { await held; await route.continue(); });
  try {
    await page.goto('/');
    await expect(page.locator('#catalog-table')).toHaveAttribute('aria-busy', 'true');
    await expect(page.locator('#catalog-rows tr')).toHaveCount(0);
    expect(await page.locator('#catalog-table').evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(576);
    expect(await page.locator('footer').evaluate(element => element.getBoundingClientRect().top)).toBeGreaterThan(900);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  } finally { release(); }
  await expect(page.locator('#catalog-rows tr[data-provenance="curated"]')).toHaveCount(6);
  await expect(page.locator('#catalog-table')).toHaveAttribute('aria-busy', 'false');
  await expect(page.locator('#catalog-table')).not.toHaveAttribute('data-initial-loading');
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  expect(await page.evaluate(() => globalThis.catalogLayoutShift)).toBeLessThan(.01);
  await page.route('**/api/catalog?*', route => route.fulfill({
    status: 503, json: { error: 'Catalog temporarily unavailable. Retry the search.', code: 'database_unavailable' }
  }));
  await page.locator('#catalog-search').fill('temporarily unavailable');
  await expect(page.locator('#catalog-status')).toContainText('Catalog temporarily unavailable');
  await expect(page.locator('#catalog-table')).toHaveAttribute('aria-busy', 'false');
  await expect(page.locator('#catalog-rows tr[data-provenance="curated"]')).toHaveCount(6);
});
