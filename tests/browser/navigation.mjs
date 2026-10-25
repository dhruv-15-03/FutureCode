import { expect } from '@playwright/test';

export async function openMode(page, id) {
  const control = page.locator(`#${id}`);
  const route = await control.getAttribute('data-route');
  if (!await control.isVisible()) await page.locator('#contents-toggle').click();
  await control.click();
  await expect(page.locator('body')).toHaveAttribute('data-view', route);
  await expect(page.locator('#contents-toggle')).toHaveAttribute('aria-expanded', 'false');
}
