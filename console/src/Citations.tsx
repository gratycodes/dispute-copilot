import type { Evidence, Transaction } from './api';
import { day, money } from './format';
import { POLICY } from './policy';

interface Props {
  citations: string[];
  evidence: Evidence[];
  transactions: Transaction[];
}

function describeEvidence(e: Evidence): string {
  const p = e.payload as Record<string, string | number | undefined>;
  switch (e.kind) {
    case 'TRACKING':
      return `${p.carrier} ${p.tracking_number}: ${String(p.status).toLowerCase()}${p.delivered_at ? ` ${day(String(p.delivered_at))}` : ''}${
        p.delivered_to ? ` to ${p.delivered_to}` : ''
      }${p.expected_delivery ? `, expected ${day(String(p.expected_delivery))}` : ''}`;
    case 'REFUND_RECORD':
      return `Refund issued ${day(String(p.refunded_at))}`;
    case 'RETURN_RECEIVED':
      return `Return ${p.rma} received ${day(String(p.received_at))}`;
    case 'CANCELLATION_RECORD':
      return `Cancelled ${day(String(p.cancelled_at))}`;
    case 'USAGE_LOG':
      return `${p.sessions_after_charge} sessions after the charge, last used ${day(String(p.last_used_at))}`;
    case 'PRODUCT_DESCRIPTION':
      return `${p.title}: ${p.description_at_checkout}`;
    default:
      return e.kind.toLowerCase();
  }
}

export function Citations({ citations, evidence, transactions }: Props) {
  if (citations.length === 0) return <p className="muted">No citations.</p>;
  return (
    <ul className="citations">
      {citations.map((c) => {
        const policy = POLICY.get(c);
        if (policy)
          return (
            <li key={c} className="cite policy">
              <span className="cite-id">{c}</span> <strong>{policy.title}.</strong> <span className="muted">{policy.text}</span>
            </li>
          );
        const ev = evidence.find((e) => e.id === c);
        if (ev)
          return (
            <li key={c} className="cite evidence">
              <span className="cite-id">{c}</span> Merchant evidence: {describeEvidence(ev)}
            </li>
          );
        const t = transactions.find((x) => x.id === c);
        if (t)
          return (
            <li key={c} className="cite txn">
              <span className="cite-id">{c}</span> Transaction {day(t.created_at)}, {money(t.amount_minor, t.currency)}, order {t.order_ref}, 3-D
              Secure {t.three_ds.toLowerCase()}, device {t.device_id}
              {t.disputed ? ', disputed' : ''}
            </li>
          );
        return (
          <li key={c} className="cite">
            <span className="cite-id">{c}</span>
          </li>
        );
      })}
    </ul>
  );
}
