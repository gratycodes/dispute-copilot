import { useState } from 'react';
import { ApiError, DECISIONS, api, type Decision, type Recommendation } from './api';
import { decisionKey, validateDecision } from './decision';
import { label } from './format';

interface Props {
  disputeId: string;
  recommendation: Recommendation;
  onDecided: () => void;
}

export function DecisionForm({ disputeId, recommendation, onDecided }: Props) {
  const [chosen, setChosen] = useState<Decision>(recommendation.decision);
  const [reason, setReason] = useState('');
  const [reviewer, setReviewer] = useState('analyst@example.test');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // One nonce per mounted form: retries of the same choice reuse the key.
  const [nonce] = useState(() => crypto.randomUUID().slice(0, 8));
  const override = chosen !== recommendation.decision;
  const problem = validateDecision(recommendation.decision, chosen, reason);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (problem || busy) return;
    setBusy(true);
    setError(null);
    try {
      await api.decide(
        disputeId,
        { recommendation_id: recommendation.id, decision: chosen, reason: reason.trim() || null, reviewer },
        decisionKey(recommendation.id, chosen, reason, nonce),
      );
      onDecided();
    } catch (err) {
      setError(err instanceof ApiError && err.status === 409 ? `Someone else changed this dispute: ${err.message}` : String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="decide" onSubmit={submit} aria-label="Decision">
      <fieldset>
        <legend>Your decision</legend>
        <div className="choices">
          {DECISIONS.map((d) => (
            <label key={d} className={d === chosen ? 'choice on' : 'choice'}>
              <input type="radio" name="decision" value={d} checked={d === chosen} onChange={() => setChosen(d)} />
              {label(d)}
              {d === recommendation.decision && <span className="muted small"> (agent)</span>}
            </label>
          ))}
        </div>
        <label className="field">
          {override ? 'Why are you overriding the agent? (required, stored as a label)' : 'Note (optional)'}
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} />
        </label>
        <label className="field inline">
          Reviewer
          <input value={reviewer} onChange={(e) => setReviewer(e.target.value)} />
        </label>
      </fieldset>
      {override && problem && reason.length > 0 && <p className="error">{problem}</p>}
      {error && <p className="error">{error}</p>}
      <button type="submit" className="primary" disabled={!!problem || busy || !reviewer.trim()}>
        {override ? `Override: ${label(chosen)}` : `Approve: ${label(chosen)}`}
      </button>
    </form>
  );
}
