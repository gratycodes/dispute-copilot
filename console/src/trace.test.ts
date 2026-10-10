import { describe, expect, it } from 'vitest';
import trace from './fixtures/trace-dsp_0001.json';
import { flattenTrace, totals } from './trace';

describe('flattenTrace', () => {
  const rows = flattenTrace(trace);

  it('nests spans under the triage root in start order', () => {
    expect(rows[0]!.name).toBe('triage_dispute dsp_0001');
    expect(rows[0]!.depth).toBe(0);
    const agent = rows.find((r) => r.name === 'invoke_agent dispute-triage')!;
    expect(agent.depth).toBe(1);
    const chats = rows.filter((r) => r.name.startsWith('chat '));
    expect(chats).toHaveLength(3);
    expect(chats.every((c) => c.depth === 2 && c.parentId === agent.spanId)).toBe(true);
    expect(rows.at(-1)!.name).toBe('guard recommendation');
    for (let i = 1; i < rows.length; i++) expect(rows[i]!.startMs).toBeGreaterThanOrEqual(0);
  });

  it('sums model usage and counts tool calls', () => {
    const t = totals(rows);
    expect(t.modelCalls).toBe(3);
    expect(t.toolCalls).toBe(6);
    expect(t.input).toBeGreaterThan(500);
    expect(t.output).toBeGreaterThan(0);
  });

  it('decodes attribute types', () => {
    const root = rows[0]!;
    expect(root.attrs['dispute.recommendation']).toBe('REPRESENT');
    expect(typeof root.attrs['dispute.confidence']).toBe('number');
    const guard = rows.find((r) => r.name === 'guard recommendation')!;
    expect(Array.isArray(guard.attrs['guard.flags'])).toBe(true);
  });

  it('handles empty or malformed input', () => {
    expect(flattenTrace({})).toEqual([]);
    expect(flattenTrace(null)).toEqual([]);
  });
});
