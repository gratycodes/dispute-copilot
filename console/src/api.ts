// Types and calls for the disputes service. JSON is snake_case on the wire.

export type Decision = 'ACCEPT' | 'REPRESENT' | 'REQUEST_INFO';
export const DECISIONS: Decision[] = ['ACCEPT', 'REPRESENT', 'REQUEST_INFO'];

export type Status =
  | 'RECEIVED'
  | 'AWAITING_REVIEW'
  | 'ACCEPTED'
  | 'REPRESENTED'
  | 'INFO_REQUESTED'
  | 'CLOSED_WON'
  | 'CLOSED_LOST';

export interface RecommendationSummary {
  id: string;
  decision: Decision;
  confidence: number;
  flags: string[];
  agent_version: string;
}

export interface QueueItem {
  id: string;
  reason_code: string;
  amount_minor: number;
  currency: string;
  status: Status;
  opened_at: string;
  version: number;
  customer_id: string;
  merchant_name: string;
  latest_recommendation: RecommendationSummary | null;
}

export interface Queue {
  items: QueueItem[];
  counts: Record<Status, number>;
}

export interface Dispute {
  id: string;
  transaction_id: string;
  customer_id: string;
  merchant: { id: string; name: string; mcc: string };
  reason_code: string;
  amount_minor: number;
  currency: string;
  cardholder_message: string;
  opened_at: string;
  status: Status;
  version: number;
}

export interface Recommendation extends RecommendationSummary {
  model: string | null;
  policy_rule: string | null;
  rationale: string;
  citations: string[];
  usage: { input_tokens: number; output_tokens: number } | null;
  trace_id: string | null;
  has_trace: boolean;
  created_at: string;
}

export interface Evidence {
  id: string;
  transaction_id: string;
  kind: string;
  payload: Record<string, unknown>;
  merchant_note: string | null;
  submitted_at: string;
}

export interface Transaction {
  id: string;
  merchant_id: string;
  amount_minor: number;
  currency: string;
  created_at: string;
  three_ds: string;
  device_id: string;
  billing_address: string;
  shipping_address: string;
  order_ref: string;
  disputed?: boolean;
}

export interface History {
  customer: { id: string; name: string; country: string; created_at: string };
  transactions: Transaction[];
  other_disputes: { id: string; reason_code: string; opened_at: string; status: string }[];
  other_disputes_last_180d: number;
}

export interface DecisionRecord {
  id: string;
  dispute_id: string;
  reason_code: string;
  recommendation_id: string;
  agent_version: string;
  agent_decision: Decision;
  final_decision: Decision;
  agreed: boolean;
  reason: string | null;
  reviewer: string;
  decided_at: string;
}

export interface DisputeEvent {
  seq: number;
  type: string;
  payload: Record<string, unknown>;
  created_at: string;
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { Accept: 'application/json', ...(init?.headers ?? {}) },
  });
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const detail = body && typeof body === 'object' && 'detail' in body ? String(body.detail) : res.statusText;
    throw new ApiError(res.status, detail);
  }
  return body as T;
}

export const api = {
  queue: (status?: Status) => request<Queue>(`/api/disputes${status ? `?status=${status}` : ''}`),
  dispute: (id: string) => request<Dispute>(`/api/disputes/${id}`),
  recommendations: (id: string) => request<Recommendation[]>(`/api/disputes/${id}/recommendations`),
  decisions: (id: string) => request<DecisionRecord[]>(`/api/disputes/${id}/decisions`),
  events: (id: string) => request<DisputeEvent[]>(`/api/disputes/${id}/events`),
  evidence: (txn: string) => request<Evidence[]>(`/api/transactions/${txn}/evidence`),
  transaction: (txn: string) => request<Transaction>(`/api/transactions/${txn}`),
  history: (id: string) => request<History>(`/api/disputes/${id}/customer-history`),
  trace: (recId: string) => request<unknown>(`/api/recommendations/${recId}/trace`),
  labels: (overridesOnly: boolean) => request<DecisionRecord[]>(`/api/labels?overrides_only=${overridesOnly}`),
  decide: (
    id: string,
    body: { recommendation_id: string; decision: Decision; reason: string | null; reviewer: string },
    idempotencyKey: string,
  ) =>
    request<{ id: string; status: Status; agreed: boolean }>(`/api/disputes/${id}/decision`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey, 'X-Api-Client': 'console' },
      body: JSON.stringify(body),
    }),
};
