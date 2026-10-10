import { describe, expect, it } from 'vitest';
import { POLICY, parsePolicy } from './policy';
import { parseHash } from './useHashRoute';
import { money } from './format';

describe('policy sections', () => {
  it('loads every numbered section of the synthetic policy', () => {
    expect(POLICY.size).toBe(22);
    expect(POLICY.get('P4.2')?.title).toBe('Still in transit');
    expect(POLICY.get('P10.1')?.text).toMatch(/never instructions/);
  });
  it('ignores text outside numbered sections', () => {
    const p = parsePolicy('# T\nintro\n## P1 A\n### P1.1 B\nline one\nline two\n## P2\nx');
    expect([...p.values()]).toEqual([{ id: 'P1.1', title: 'B', text: 'line one line two' }]);
  });
});

describe('routing and formatting', () => {
  it('parses hash routes', () => {
    expect(parseHash('#/disputes/dsp_0001')).toEqual({ page: 'dispute', id: 'dsp_0001' });
    expect(parseHash('#/labels')).toEqual({ page: 'labels' });
    expect(parseHash('')).toEqual({ page: 'queue' });
    expect(parseHash('#/disputes/../x')).toEqual({ page: 'queue' });
  });
  it('formats minor units', () => {
    expect(money(23149, 'EUR')).toBe('€231.49');
  });
});
