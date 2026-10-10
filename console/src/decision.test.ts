import { describe, expect, it } from 'vitest';
import { decisionKey, validateDecision } from './decision';

describe('validateDecision', () => {
  it('approving needs no reason', () => {
    expect(validateDecision('ACCEPT', 'ACCEPT', '')).toBeNull();
  });
  it('overriding needs a reason of 10+ characters', () => {
    expect(validateDecision('ACCEPT', 'REPRESENT', '')).toMatch(/needs a reason/);
    expect(validateDecision('ACCEPT', 'REPRESENT', '   too short ')).toMatch(/needs a reason/);
    expect(validateDecision('ACCEPT', 'REPRESENT', 'Signed POD on file')).toBeNull();
  });
});

describe('decisionKey', () => {
  it('is stable for the same submission and changes with the choice or reason', () => {
    const a = decisionKey('rec_1', 'ACCEPT', 'x', 'n1');
    expect(decisionKey('rec_1', 'ACCEPT', ' x ', 'n1')).toBe(a);
    expect(decisionKey('rec_1', 'REPRESENT', 'x', 'n1')).not.toBe(a);
    expect(decisionKey('rec_1', 'ACCEPT', 'y', 'n1')).not.toBe(a);
    expect(decisionKey('rec_2', 'ACCEPT', 'x', 'n1')).not.toBe(a);
    expect(a.length).toBeLessThan(255);
  });
});
