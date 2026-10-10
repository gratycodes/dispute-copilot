import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Recommendation } from './api';
import { DecisionForm } from './DecisionForm';

const rec: Recommendation = {
  id: 'rec_1',
  decision: 'REPRESENT',
  confidence: 0.85,
  flags: [],
  agent_version: 'v2',
  model: 'm',
  policy_rule: 'P4.1',
  rationale: 'r',
  citations: [],
  usage: null,
  trace_id: null,
  has_trace: false,
  created_at: '2026-10-10T00:00:00Z',
};

function mockFetch(status: number, body: unknown) {
  const fn = vi.fn(async () => new Response(JSON.stringify(body), { status }));
  vi.stubGlobal('fetch', fn);
  return fn;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('DecisionForm', () => {
  it('approves the agent recommendation without a reason', async () => {
    const fetch = mockFetch(201, { id: 'dec_1', status: 'REPRESENTED', agreed: true });
    const done = vi.fn();
    render(<DecisionForm disputeId="dsp_1" recommendation={rec} onDecided={done} />);
    fireEvent.click(screen.getByRole('button', { name: /approve: represent/i }));
    await waitFor(() => expect(done).toHaveBeenCalled());
    const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/api/disputes/dsp_1/decision');
    const headers = init.headers as Record<string, string>;
    expect(headers['Idempotency-Key']).toMatch(/^dec:rec_1:/);
    expect(JSON.parse(String(init.body))).toMatchObject({ recommendation_id: 'rec_1', decision: 'REPRESENT', reason: null });
  });

  it('requires a reason before an override can be submitted', async () => {
    const fetch = mockFetch(201, { id: 'dec_1', status: 'ACCEPTED', agreed: false });
    render(<DecisionForm disputeId="dsp_1" recommendation={rec} onDecided={() => {}} />);
    fireEvent.click(screen.getByLabelText(/^accept/i));
    const button = screen.getByRole('button', { name: /override: accept/i });
    expect(button).toHaveProperty('disabled', true);
    fireEvent.change(screen.getByRole('textbox', { name: /why are you overriding/i }), { target: { value: 'short' } });
    expect(screen.getByText(/needs a reason/)).toBeTruthy();
    fireEvent.change(screen.getByRole('textbox', { name: /why are you overriding/i }), {
      target: { value: 'Tracking shows a different postcode.' },
    });
    expect(button).toHaveProperty('disabled', false);
    fireEvent.click(button);
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    expect(JSON.parse(String((fetch.mock.calls[0] as unknown as [string, RequestInit])[1].body))).toMatchObject({
      decision: 'ACCEPT',
      reason: 'Tracking shows a different postcode.',
    });
  });

  it('explains a conflict when the dispute changed underneath the reviewer', async () => {
    mockFetch(409, { detail: 'recommendation rec_1 is not the latest' });
    render(<DecisionForm disputeId="dsp_1" recommendation={rec} onDecided={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /approve/i }));
    expect(await screen.findByText(/someone else changed this dispute/i)).toBeTruthy();
  });
});
