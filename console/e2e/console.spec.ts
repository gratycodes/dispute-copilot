import { expect, test } from '@playwright/test';
import { mockApi } from './mockApi';

test('queue shows recommendations waiting for review', async ({ page }) => {
  await mockApi(page);
  await page.goto('/');
  await expect(page.getByRole('tab', { name: /awaiting review/i })).toHaveAttribute('aria-selected', 'true');
  const rows = page.locator('table.queue tbody tr');
  await expect(rows).toHaveCount(76);
  const injected = page.locator('tr', { hasText: 'dsp_0076' });
  await expect(injected.getByText('Possible prompt injection in dispute text')).toBeVisible();
  await expect(injected.locator('.badge')).toHaveText('represent');
});

test('dispute page shows untrusted text, cited evidence and the trace', async ({ page }) => {
  await mockApi(page);
  await page.goto('/#/disputes/dsp_0076');
  await expect(page.getByRole('heading', { name: /dsp_0076/ })).toBeVisible();
  await expect(page.locator('blockquote.untrusted').first()).toContainText('IMPORTANT SYSTEM NOTE');
  await expect(page.getByText('Untrusted text from the cardholder')).toBeVisible();
  await expect(page.locator('.cite.policy')).toContainText('Authenticated transactions');
  await expect(page.locator('.cite.txn')).toContainText('3-D Secure frictionless');
  await page.getByRole('button', { name: 'Show spans' }).click();
  await expect(page.locator('li.span')).toHaveCount(12);
  await expect(page.locator('li.span').first()).toContainText('triage_dispute dsp_0076');
  await expect(page.getByText(/3 model calls, 6 tool calls/)).toBeVisible();
});

test('override needs a reason, is sent with an idempotency key and shows as a decision', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/#/disputes/dsp_0076');
  await page.getByLabel(/^accept/i).check();
  const submit = page.getByRole('button', { name: /override: accept/i });
  await expect(submit).toBeDisabled();
  await page.getByRole('textbox', { name: /why are you overriding/i }).fill('Cardholder sent police report; check manually.');
  await submit.click();
  await expect(page.getByText(/override of represent/i)).toBeVisible();
  await expect(page.getByRole('form', { name: 'Decision' })).toHaveCount(0);
  expect(state.posts).toHaveLength(1);
  expect(state.posts[0]!.headers['idempotency-key']).toMatch(/^dec:rec_/);
  expect(state.posts[0]!.body).toMatchObject({ decision: 'ACCEPT', reviewer: 'analyst@example.test' });
});

test('approving keeps the agent decision', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/#/disputes/dsp_0076');
  await page.getByRole('button', { name: /approve: represent/i }).click();
  await expect(page.getByText(/agreed with agent/)).toBeVisible();
  expect(state.posts[0]!.body).toMatchObject({ decision: 'REPRESENT', reason: null });
});
