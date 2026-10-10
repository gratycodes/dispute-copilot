import { api } from './api';
import { DecisionBadge } from './Badges';
import { day, label } from './format';
import { useLoad } from './useLoad';

export function LabelsPage() {
  const { data, error } = useLoad(() => api.labels(true), 'overrides');
  return (
    <section>
      <h1>Overrides</h1>
      <p className="muted">
        Every time a reviewer disagrees with the agent, the case and the reason are kept as a label. They feed the next golden set. Export:{' '}
        <a href="/api/labels?overrides_only=true">/api/labels?overrides_only=true</a>
      </p>
      {error && <p className="error">{error}</p>}
      {data && data.length === 0 && <p className="muted">No overrides yet.</p>}
      {data && data.length > 0 && (
        <table className="queue">
          <thead>
            <tr>
              <th>Dispute</th>
              <th>Reason code</th>
              <th>Agent</th>
              <th>Reviewer chose</th>
              <th>Why</th>
              <th>By</th>
              <th>When</th>
            </tr>
          </thead>
          <tbody>
            {data.map((x) => (
              <tr key={x.id}>
                <td>
                  <a href={`#/disputes/${x.dispute_id}`}>{x.dispute_id}</a>
                </td>
                <td>{label(x.reason_code)}</td>
                <td>
                  <DecisionBadge decision={x.agent_decision} /> <span className="muted small">{x.agent_version}</span>
                </td>
                <td>
                  <DecisionBadge decision={x.final_decision} />
                </td>
                <td>{x.reason}</td>
                <td>{x.reviewer}</td>
                <td>{day(x.decided_at)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
