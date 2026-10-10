import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Citations } from './Citations';

afterEach(cleanup);

describe('Citations', () => {
  it('explains policy sections, evidence and transactions', () => {
    render(
      <Citations
        citations={['P4.1', 'ev_1', 'txn_1', 'zzz']}
        evidence={[
          {
            id: 'ev_1',
            transaction_id: 'txn_1',
            kind: 'TRACKING',
            payload: { carrier: 'SwiftPost', tracking_number: 'TRK1', status: 'DELIVERED', delivered_at: '2026-09-02T00:00:00Z', delivered_to: '1 Elm Road' },
            merchant_note: null,
            submitted_at: '2026-09-02T00:00:00Z',
          },
        ]}
        transactions={[
          {
            id: 'txn_1',
            merchant_id: 'm',
            amount_minor: 4999,
            currency: 'EUR',
            created_at: '2026-09-01T00:00:00Z',
            three_ds: 'CHALLENGE',
            device_id: 'dev_1',
            billing_address: 'a',
            shipping_address: 'a',
            order_ref: 'ORD-1',
          },
        ]}
      />,
    );
    expect(screen.getByText('Delivery confirmed.')).toBeTruthy();
    expect(screen.getByText(/SwiftPost TRK1: delivered 2026-09-02 to 1 Elm Road/)).toBeTruthy();
    expect(screen.getByText(/order ORD-1, 3-D Secure challenge/)).toBeTruthy();
    expect(screen.getByText('zzz')).toBeTruthy();
  });

  it('says when there is nothing cited', () => {
    render(<Citations citations={[]} evidence={[]} transactions={[]} />);
    expect(screen.getByText('No citations.')).toBeTruthy();
  });
});
