import { useState } from 'react';
import { api } from './api';
import { flattenTrace, totals } from './trace';

export function TracePanel({ recommendationId, traceId }: { recommendationId: string; traceId: string | null }) {
  const [rows, setRows] = useState<ReturnType<typeof flattenTrace> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const open = () =>
    api.trace(recommendationId).then(
      (doc) => setRows(flattenTrace(doc)),
      (e: unknown) => setError(String(e)),
    );
  const span = rows ? Math.max(...rows.map((r) => r.startMs + r.durationMs), 0.001) : 1;
  const t = rows ? totals(rows) : null;
  return (
    <div className="trace">
      <div className="trace-head">
        <span>
          Trace <code>{traceId ?? 'n/a'}</code>
        </span>
        {!rows && (
          <button className="link" onClick={open}>
            Show spans
          </button>
        )}
        <a className="link" href={`/api/recommendations/${recommendationId}/trace`} download>
          Download OTLP JSON
        </a>
      </div>
      {error && <p className="error">{error}</p>}
      {rows && t && (
        <>
          <p className="muted small">
            {t.modelCalls} model calls, {t.toolCalls} tool calls, {t.input.toLocaleString()} input / {t.output.toLocaleString()} output tokens
            (estimated for the deterministic model). Open the file in genai-trace-diff for a full timeline.
          </p>
          <ol className="spans">
            {rows.map((r) => (
              <li key={r.spanId} className={r.error ? 'span err' : 'span'}>
                <span className="span-name" style={{ paddingLeft: `${r.depth * 14}px` }}>
                  {r.name}
                </span>
                <span className="bar-wrap">
                  <span className="bar" style={{ left: `${(r.startMs / span) * 100}%`, width: `${Math.max((r.durationMs / span) * 100, 0.5)}%` }} />
                </span>
                <span className="span-ms">{r.durationMs.toFixed(1)} ms</span>
              </li>
            ))}
          </ol>
        </>
      )}
    </div>
  );
}
