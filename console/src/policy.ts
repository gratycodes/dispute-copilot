// Section titles and text from the synthetic policy, for citation tooltips.
import policyMarkdown from '../../policy/dispute-policy.md?raw';

export interface PolicySection {
  id: string;
  title: string;
  text: string;
}

export function parsePolicy(md: string): Map<string, PolicySection> {
  const out = new Map<string, PolicySection>();
  let current: PolicySection | null = null;
  for (const line of md.split('\n')) {
    const m = /^###\s+(P\d+\.\d+)\s+(.*)$/.exec(line);
    if (m) {
      current = { id: m[1]!, title: m[2]!.trim(), text: '' };
      out.set(current.id, current);
    } else if (line.startsWith('#')) {
      current = null;
    } else if (current && line.trim()) {
      current.text = `${current.text} ${line.trim()}`.trim();
    }
  }
  return out;
}

export const POLICY = parsePolicy(policyMarkdown);
