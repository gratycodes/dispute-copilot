import type { Decision } from './api';

export const MIN_OVERRIDE_REASON = 10;

/** Mirrors the service rule: overriding the agent needs a written reason. */
export function validateDecision(recommended: Decision, chosen: Decision, reason: string): string | null {
  if (chosen === recommended) return null;
  if (reason.trim().length < MIN_OVERRIDE_REASON) {
    return `Overriding the recommendation needs a reason (at least ${MIN_OVERRIDE_REASON} characters).`;
  }
  return null;
}

/**
 * One idempotency key per intended decision. A retry of the same submission (double click,
 * network timeout) reuses the key, so the service replays the first response instead of
 * recording a second decision. Changing the decision or reason makes a new key.
 */
export function decisionKey(recommendationId: string, chosen: Decision, reason: string, nonce: string): string {
  let h = 0;
  for (const ch of `${chosen}|${reason.trim()}`) h = (Math.imul(31, h) + ch.charCodeAt(0)) | 0;
  return `dec:${recommendationId}:${nonce}:${(h >>> 0).toString(16)}`;
}
