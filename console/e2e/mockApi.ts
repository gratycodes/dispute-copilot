import type { Page, Route } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const dir = join(import.meta.dirname, 'fixtures');
const load = (name: string) => JSON.parse(readFileSync(join(dir, name), 'utf8'));

/** Serve captured API responses; the decision POST updates the in-memory state like the service does. */
export async function mockApi(page: Page) {
  const state = {
    queue: load('queue-awaiting.json'),
    dispute: load('dsp_0076.json'),
    recs: load('dsp_0076-recommendations.json'),
    decisions: [] as unknown[],
    events: load('dsp_0076-events.json'),
    posts: [] as { headers: Record<string, string>; body: Record<string, unknown> }[],
  };
  const json = (route: Route, body: unknown, status = 200) =>
    route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url());
    const p = url.pathname;
    if (route.request().method() === 'POST' && p === '/api/disputes/dsp_0076/decision') {
      const body = route.request().postDataJSON();
      state.posts.push({ headers: route.request().headers(), body });
      const agreed = body.decision === state.recs[0].decision;
      state.dispute = { ...state.dispute, status: body.decision === 'ACCEPT' ? 'ACCEPTED' : body.decision === 'REPRESENT' ? 'REPRESENTED' : 'INFO_REQUESTED' };
      state.decisions = [
        { id: 'dec_1', dispute_id: 'dsp_0076', reason_code: 'FRAUD_CNP', recommendation_id: state.recs[0].id, agent_version: 'v2',
          agent_decision: state.recs[0].decision, final_decision: body.decision, agreed, reason: body.reason, reviewer: body.reviewer,
          decided_at: '2026-10-10T18:30:00Z' },
      ];
      return json(route, { id: 'dec_1', status: state.dispute.status, agreed }, 201);
    }
    if (p === '/api/disputes') {
      const status = url.searchParams.get('status');
      if (status === 'AWAITING_REVIEW') return json(route, state.queue);
      return json(route, { items: [], counts: state.queue.counts });
    }
    if (p === '/api/disputes/dsp_0076') return json(route, state.dispute);
    if (p === '/api/disputes/dsp_0076/recommendations') return json(route, state.recs);
    if (p === '/api/disputes/dsp_0076/decisions') return json(route, state.decisions);
    if (p === '/api/disputes/dsp_0076/events') return json(route, state.events);
    if (p === '/api/disputes/dsp_0076/customer-history') return json(route, load('dsp_0076-customer-history.json'));
    if (p.endsWith('/evidence')) return json(route, load('evidence.json'));
    if (p.startsWith('/api/transactions/')) return json(route, load('txn.json'));
    if (p.endsWith('/trace')) return json(route, load('trace.json'));
    if (p === '/api/labels') return json(route, state.decisions.filter((d) => !(d as { agreed: boolean }).agreed));
    return json(route, { detail: 'not mocked' }, 404);
  });
  return state;
}
