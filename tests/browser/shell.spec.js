import { test, expect } from '@playwright/test';
import { openMode } from './navigation.mjs';

async function assertPageFrame(page) {
  await expect(page.getByRole('banner')).toHaveCount(1);
  await expect(page.getByRole('contentinfo')).toHaveCount(1);
  const geometry = await page.evaluate(() => {
    const position = element => {
      const rect = element.getBoundingClientRect();
      return { top: rect.top + scrollY, bottom: rect.bottom + scrollY, left: rect.left, width: rect.width };
    };
    const main = document.querySelector('main'), footer = document.querySelector('.site-footer');
    return {
      header: position(document.querySelector('.book-header')), main: position(main), footer: position(footer),
      width: document.documentElement.clientWidth, height: document.documentElement.scrollHeight,
      viewport: innerHeight, overflow: document.documentElement.scrollWidth > innerWidth,
      footerInsideMain: main.contains(footer), footerPosition: getComputedStyle(footer).position
    };
  });
  expect(geometry.header.top).toBeCloseTo(0, 0);
  expect(geometry.header.left).toBeCloseTo(0, 0);
  expect(geometry.header.width).toBeCloseTo(geometry.width, 0);
  expect(geometry.header.bottom - geometry.header.top).toBeLessThan(geometry.width > 760 ? 144 : 200);
  expect(geometry.footer.left).toBeCloseTo(0, 0);
  expect(geometry.footer.width).toBeCloseTo(geometry.width, 0);
  expect(geometry.footer.top).toBeGreaterThanOrEqual(geometry.main.bottom - 1);
  expect(Math.abs(geometry.footer.bottom - geometry.height)).toBeLessThanOrEqual(1);
  expect(geometry.footer.bottom).toBeGreaterThanOrEqual(geometry.viewport - 1);
  expect(geometry.footerInsideMain).toBe(false);
  expect(['static', 'relative']).toContain(geometry.footerPosition);
  expect(geometry.overflow).toBe(false);
}

for (const width of [1440, 390]) {
  test(`shared header and footer frame short, empty and long views at ${width}px`, async ({ page }, testInfo) => {
    test.setTimeout(60_000);
    await page.setViewportSize({ width, height: 900 });
    await page.setExtraHTTPHeaders({ 'X-Test-Role': 'guest' });
    for (const route of ['account', 'attempts', 'contribute', 'review', 'problem/not-a-problem']) {
      await page.goto(`/#${route}`);
      await expect(page.locator('#sign-in-action')).not.toBeDisabled();
      await assertPageFrame(page);
    }
    await page.goto('/#account');
    await expect(page.getByRole('button', { name: 'Continue with Google' })).toBeVisible();
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: testInfo.outputPath(`account-${width}.png`), fullPage: true });
    await page.getByRole('contentinfo').getByRole('link', { name: 'Interview case', exact: true }).click();
    await expect(page.locator('#case-title')).toBeVisible();
    await assertPageFrame(page);
    await page.setExtraHTTPHeaders({});
    await page.goto('/#attempts');
    await page.reload();
    await expect(page.locator('#attempts-content')).toContainText('No saved attempts yet');
    await assertPageFrame(page);
    await page.goto('/#contribute/new');
    await expect(page.locator('#problem-title')).toBeVisible();
    await assertPageFrame(page);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: testInfo.outputPath(`editor-${width}.png`), fullPage: true });
    await page.goto('/');
    await expect(page.locator('#catalog-rows tr[data-provenance="curated"]')).toHaveCount(6);
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await expect(page.locator('#guide-first')).toHaveText('The problemset');
    await assertPageFrame(page);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: testInfo.outputPath(`catalog-${width}.png`), fullPage: true });
  });
}

test('the footer links work on the app and the no-JavaScript privacy document', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#catalog-rows tr[data-provenance="curated"]')).toHaveCount(6);
  await page.getByRole('contentinfo').getByRole('link', { name: 'Your account', exact: true }).click();
  await expect(page.locator('#account-view')).toBeVisible();
  await page.getByRole('contentinfo').getByRole('link', { name: 'Privacy & data', exact: true }).click();
  await expect(page).toHaveURL(/\/privacy$/);
  await expect(page).toHaveTitle('Privacy & data | FutureCode');
  await expect(page.locator('h1')).toHaveText('Privacy & data');
  await assertPageFrame(page);
  await page.getByRole('banner').getByRole('link', { name: 'Back to practice', exact: true }).click();
  await expect(page.locator('#catalog-view')).toBeVisible();
});

test('the compact header and contents remain usable on a narrow screen', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await page.goto('/#account');
  await expect(page.locator('#profile-name')).toBeVisible();
  await assertPageFrame(page);
  await page.locator('#profile-name').fill('An unsaved public name');
  await page.locator('#contents-toggle').click();
  await expect(page.locator('#contents-toggle')).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('#catalog-mode')).toBeFocused();
  await assertPageFrame(page);
  await page.keyboard.press('Escape');
  await expect(page.locator('#contents-toggle')).toBeFocused();
  await expect(page.locator('#profile-name')).toHaveValue('An unsaved public name');
  await openMode(page, 'private-mode');
  await expect(page.locator('#snippet')).toBeVisible();
  await assertPageFrame(page);
});

for (const width of [768, 1440]) {
  test(`the masthead keeps its default height with the narrower serif fallback at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await expect(page.locator('#sign-in-action')).not.toBeDisabled();
    await page.evaluate(() => { document.documentElement.style.fontFamily = '"Times New Roman", serif'; });
    const geometry = await page.locator('.book-header').evaluate(header => {
      const tagline = header.querySelector('.brand-tagline');
      return { header: header.getBoundingClientRect().height, tagline: tagline.getBoundingClientRect().height,
        lineHeight: parseFloat(getComputedStyle(tagline).lineHeight) };
    });
    expect(geometry.tagline).toBeLessThanOrEqual(geometry.lineHeight + 1);
    expect(geometry.header).toBeCloseTo(80, 0);
  });
}
