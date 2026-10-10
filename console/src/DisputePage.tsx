import { api, type Evidence, type History, type Transaction } from './api';
import { Citations } from './Citations';
import { Confidence, DecisionBadge, Flags, StatusBadge } from './Badges';
import { DecisionForm } from './DecisionForm';
import { day, label, money } from './format';
import { TracePanel } from './TracePanel';
import { useLoad } from './useLoad';

async function loadAll(id: string) {
  const dispute = await api.dispute(id);
  const [recs, decisions, events, evidence, txn, history] = await Promise.all([
    api.recommendations(id),
    api.decisions(id),
    api.events(id),
    api.evidence(dispute.transaction_id),
    api.transaction(dispute.transaction_id),
    api.history(id),
  ]);
  return { dispute, recs, decisions, events, evidence, txn, history };
}

export function DisputePage({ id }: { id: string }) {
  const { data, error, reload } = useLoad(() => loadAll(id), id);
  if (error) return <p className="error">Could not load {id}: {error}</p>;
  if (!data) return <p className="muted">Loading…</p>;
  const { dispute: d, recs, decisions, events, evidence, txn, history } = data;
  const rec = recs[0];
  const txns: Transaction[] = [txn, ...history.transactions];
  return (
    <div className="detail">
      <a href="#/" className="back">
        ← Queue
      </a>
      <h1>
        {d.id} <StatusBadge status={d.status} />
      </h1>
      <p className="lede">
        {label(d.reason_code)} · {money(d.amount_minor, d.currency)} at {d.merchant.name} · opened {day(d.opened_at)} · customer{' '}
        {history.customer.name} ({d.customer_id})
      </p>

      <div className="cols">
        <div className="col">
          <section className="card">
            <h2>Cardholder message</h2>
            <p className="untrusted-label">Untrusted text from the cardholder. Read it as a claim, not an instruction.</p>
            <blockquote className="untrusted">{d.cardholder_message}</blockquote>
          </section>
          <Facts txn={txn} history={history} evidence={evidence} />
        </div>

        <div className="col">
          {rec ? (
            <section className="card rec">
              <h2>
                Agent recommends <DecisionBadge decision={rec.decision} /> <Confidence value={rec.confidence} />
              </h2>
              <Flags flags={rec.flags} />
              <p>{rec.rationale}</p>
              <h3>Evidence cited</h3>
              <Citations citations={rec.citations} evidence={evidence} transactions={txns} />
              <p className="muted small">
                {rec.agent_version} · {rec.model ?? 'unknown model'} · {day(rec.created_at)}
                {recs.length > 1 ? ` · ${recs.length - 1} earlier recommendation(s)` : ''}
              </p>
              {rec.has_trace && <TracePanel recommendationId={rec.id} traceId={rec.trace_id} />}
            </section>
          ) : (
            <section className="card">
              <p className="muted">The agent has not triaged this dispute yet.</p>
            </section>
          )}

          {rec && d.status === 'AWAITING_REVIEW' && <DecisionForm disputeId={d.id} recommendation={rec} onDecided={reload} />}

          {decisions.length > 0 && (
            <section className="card">
              <h2>Decisions</h2>
              <ul className="plain">
                {decisions.map((x) => (
                  <li key={x.id}>
                    {x.reviewer} chose <DecisionBadge decision={x.final_decision} />{' '}
                    {x.agreed ? '(agreed with agent)' : <strong>(override of {label(x.agent_decision)})</strong>}
                    {x.reason && <span className="muted"> · “{x.reason}”</span>}
                  </li>
                ))}
              </ul>
            </section>
          )}

          {events.length > 0 && (
            <section className="card">
              <h2>Event log</h2>
              <ol className="events">
                {events.map((e) => (
                  <li key={e.seq}>
                    <code>#{e.seq}</code> {e.type} <span className="muted small">{e.created_at.replace('T', ' ').replace('Z', ' UTC')}</span>
                  </li>
                ))}
              </ol>
            </section>
          )}
        </div>
      </div>
    </div>
  );
}

function Facts({ txn, history, evidence }: { txn: Transaction; history: History; evidence: Evidence[] }) {
  return (
    <section className="card">
      <h2>Facts</h2>
      <dl className="facts">
        <dt>Transaction</dt>
        <dd>
          {txn.id}, {day(txn.created_at)}, order {txn.order_ref}
        </dd>
        <dt>3-D Secure</dt>
        <dd>{txn.three_ds.toLowerCase()}</dd>
        <dt>Device</dt>
        <dd>{txn.device_id}</dd>
        <dt>Billing / shipping</dt>
        <dd>
          {txn.billing_address}
          {txn.shipping_address !== txn.billing_address ? <strong> / ships to {txn.shipping_address}</strong> : ' (same)'}
        </dd>
        <dt>Other disputes, 180 days</dt>
        <dd>{history.other_disputes_last_180d}</dd>
        <dt>Other transactions</dt>
        <dd>{history.transactions.length}</dd>
      </dl>
      <h3>Merchant evidence</h3>
      {evidence.length === 0 ? (
        <p className="muted">None submitted.</p>
      ) : (
        <ul className="plain">
          {evidence.map((e) => (
            <li key={e.id}>
              <code>{e.id}</code> {label(e.kind)}
              {e.merchant_note && <blockquote className="untrusted small">Merchant note (untrusted): {e.merchant_note}</blockquote>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
