import { expect, test } from '@playwright/test';

// Captures the README screenshots from the real stack (service + agent run). Not part of CI:
//   API_URL=http://localhost:8080 SCREENSHOTS=1 npx playwright test screenshots
test.skip(!process.env.SCREENSHOTS, 'needs the real stack; set SCREENSHOTS=1');

const out = (name: string) => `../docs/screenshots/${name}.png`;
const JUDGEMENT_CASE = process.env.OVERRIDE_DISPUTE ?? 'dsp_0012';

test('capture console screenshots', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('table.queue tbody tr').first()).toBeVisible();
  await page.screenshot({ path: out('queue'), fullPage: false });

  await page.goto('/#/disputes/dsp_0076');
  await page.getByRole('button', { name: 'Show spans' }).click();
  await expect(page.locator('li.span').first()).toBeVisible();
  await page.screenshot({ path: out('dispute-injection'), fullPage: true });

  await page.goto(`/#/disputes/${JUDGEMENT_CASE}`);
  await page.getByLabel(/^request info/i).check();
  await page
    .getByRole('textbox', { name: /why are you overriding/i })
    .fill('Cardholder quotes a return tracking number the merchant has not matched. Ask the merchant to check its returns log first.');
  await page.screenshot({ path: out('override'), fullPage: true });
  await page.getByRole('button', { name: /override: request info/i }).click();
  await expect(page.getByText(/override of represent/i)).toBeVisible();

  await page.goto('/#/labels');
  await expect(page.locator('table.queue tbody tr').first()).toBeVisible();
  await page.screenshot({ path: out('overrides'), fullPage: false });
});
