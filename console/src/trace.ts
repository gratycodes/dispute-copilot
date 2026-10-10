// Flatten an OTLP/JSON trace into rows for a waterfall view.

export interface SpanRow {
  spanId: string;
  parentId: string | null;
  name: string;
  depth: number;
  startMs: number;
  durationMs: number;
  attrs: Record<string, string | number | boolean | string[]>;
  error: boolean;
}

interface OtlpValue {
  stringValue?: string;
  intValue?: string | number;
  doubleValue?: number;
  boolValue?: boolean;
  arrayValue?: { values?: OtlpValue[] };
}

function value(v: OtlpValue): string | number | boolean | string[] {
  if (v.stringValue !== undefined) return v.stringValue;
  if (v.intValue !== undefined) return Number(v.intValue);
  if (v.doubleValue !== undefined) return v.doubleValue;
  if (v.boolValue !== undefined) return v.boolValue;
  if (v.arrayValue) return (v.arrayValue.values ?? []).map((x) => String(value(x)));
  return '';
}

interface RawSpan {
  spanId: string;
  parentSpanId?: string;
  name: string;
  startTimeUnixNano: string | number;
  endTimeUnixNano: string | number;
  attributes?: { key: string; value: OtlpValue }[];
  status?: { code?: number };
}

export function flattenTrace(doc: unknown): SpanRow[] {
  const raw: RawSpan[] = [];
  const rs = (doc as { resourceSpans?: { scopeSpans?: { spans?: RawSpan[] }[] }[] })?.resourceSpans ?? [];
  for (const r of rs) for (const s of r.scopeSpans ?? []) raw.push(...(s.spans ?? []));
  if (raw.length === 0) return [];
  const ns = (x: string | number) => Number(BigInt(String(x)) / 1000n) / 1000; // ns -> ms with µs precision
  const t0 = Math.min(...raw.map((s) => ns(s.startTimeUnixNano)));
  const ids = new Set(raw.map((s) => s.spanId));
  const children = new Map<string | null, RawSpan[]>();
  for (const s of raw) {
    const p = s.parentSpanId && ids.has(s.parentSpanId) ? s.parentSpanId : null;
    children.set(p, [...(children.get(p) ?? []), s]);
  }
  const out: SpanRow[] = [];
  const walk = (parent: string | null, depth: number) => {
    const kids = (children.get(parent) ?? []).sort((a, b) => ns(a.startTimeUnixNano) - ns(b.startTimeUnixNano));
    for (const s of kids) {
      const attrs: SpanRow['attrs'] = {};
      for (const a of s.attributes ?? []) attrs[a.key] = value(a.value);
      out.push({
        spanId: s.spanId,
        parentId: parent,
        name: s.name,
        depth,
        startMs: ns(s.startTimeUnixNano) - t0,
        durationMs: ns(s.endTimeUnixNano) - ns(s.startTimeUnixNano),
        attrs,
        error: s.status?.code === 2,
      });
      walk(s.spanId, depth + 1);
    }
  };
  walk(null, 0);
  return out;
}

export function totals(rows: SpanRow[]): { input: number; output: number; toolCalls: number; modelCalls: number } {
  let input = 0;
  let output = 0;
  let toolCalls = 0;
  let modelCalls = 0;
  for (const r of rows) {
    if (r.attrs['gen_ai.operation.name'] === 'chat') {
      modelCalls++;
      input += Number(r.attrs['gen_ai.usage.input_tokens'] ?? 0);
      output += Number(r.attrs['gen_ai.usage.output_tokens'] ?? 0);
    }
    if (r.attrs['gen_ai.operation.name'] === 'execute_tool') toolCalls++;
  }
  return { input, output, toolCalls, modelCalls };
}
