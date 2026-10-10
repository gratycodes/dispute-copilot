import type { Decision, Status } from './api';
import { label, pct } from './format';

export function DecisionBadge({ decision }: { decision: Decision }) {
  return <span className={`badge d-${decision.toLowerCase()}`}>{label(decision)}</span>;
}

export function StatusBadge({ status }: { status: Status }) {
  return <span className={`status s-${status.toLowerCase()}`}>{label(status)}</span>;
}

export function Confidence({ value }: { value: number }) {
  const level = value >= 0.75 ? 'high' : value >= 0.5 ? 'mid' : 'low';
  return (
    <span className={`conf conf-${level}`} title="Agent confidence">
      {pct(value)}
    </span>
  );
}

const FLAG_TEXT: Record<string, string> = {
  injection_suspected: 'Possible prompt injection in dispute text',
  'guard:unknown_tool_requested': 'Agent asked for a tool that does not exist',
  'guard:uncited_decision': 'Agent answer was not backed by evidence',
  'guard:unverified_citation_removed': 'Agent cited something it never looked up',
  'guard:rule_not_retrieved': 'Agent cited a policy section it did not retrieve',
  'guard:unparseable_output': 'Agent output was not valid',
  'guard:invalid_decision': 'Agent output an unknown decision',
  'guard:max_steps': 'Agent ran out of steps',
};

export function Flags({ flags }: { flags: string[] }) {
  if (flags.length === 0) return null;
  return (
    <span className="flags">
      {flags.map((f) => (
        <span key={f} className="flag" title={f}>
          {FLAG_TEXT[f] ?? f}
        </span>
      ))}
    </span>
  );
}
