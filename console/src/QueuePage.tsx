import { useState } from 'react';
import { api, type Status } from './api';
import { Confidence, DecisionBadge, Flags, StatusBadge } from './Badges';
import { day, label, money } from './format';
import { useLoad } from './useLoad';

const TABS: Status[] = ['AWAITING_REVIEW', 'RECEIVED', 'INFO_REQUESTED', 'REPRESENTED', 'ACCEPTED', 'CLOSED_WON', 'CLOSED_LOST'];

export function QueuePage() {
  const [status, setStatus] = useState<Status>('AWAITING_REVIEW');
  const { data, error } = useLoad(() => api.queue(status), status);
  return (
    <section>
      <div className="tabs" role="tablist">
        {TABS.map((s) => (
          <button key={s} role="tab" aria-selected={s === status} className={s === status ? 'tab on' : 'tab'} onClick={() => setStatus(s)}>
            {label(s)} <span className="count">{data?.counts[s] ?? ''}</span>
          </button>
        ))}
      </div>
      {error && <p className="error">Could not load the queue: {error}</p>}
      {data && data.items.length === 0 && <p className="muted">Nothing here.</p>}
      {data && data.items.length > 0 && (
        <table className="queue">
          <thead>
            <tr>
              <th>Dispute</th>
              <th>Opened</th>
              <th>Reason</th>
              <th>Merchant</th>
              <th className="num">Amount</th>
              <th>Agent recommends</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {data.items.map((d) => (
              <tr key={d.id} onClick={() => (window.location.hash = `#/disputes/${d.id}`)}>
                <td>
                  <a href={`#/disputes/${d.id}`}>{d.id}</a>
                </td>
                <td>{day(d.opened_at)}</td>
                <td>{label(d.reason_code)}</td>
                <td>{d.merchant_name}</td>
                <td className="num">{money(d.amount_minor, d.currency)}</td>
                <td>
                  {d.latest_recommendation ? (
                    <span className="rec-cell">
                      <DecisionBadge decision={d.latest_recommendation.decision} />
                      <Confidence value={d.latest_recommendation.confidence} />
                      <Flags flags={d.latest_recommendation.flags} />
                    </span>
                  ) : (
                    <span className="muted">not triaged yet</span>
                  )}
                </td>
                <td>
                  <StatusBadge status={d.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
