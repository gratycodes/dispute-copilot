"""A deterministic stand-in for the LLM, used in CI and for the eval baseline.

``PolicyTriageModel`` implements agent-runtime-kit's ``LLM`` protocol. It
drives the same tool calls an LLM would (look up the dispute, then the
transaction, history, merchant evidence and policy) and then applies the
written policy as code to the tool results. It is not a language model: it
does not read free text at all, which is also why it cannot be steered by
injected text and why it misses cases that need reading the cardholder's
message (see the "judgement" cases in the golden set).

Two versions exist so the eval can compare them:

* ``v1`` knows the reason-code rules only (no small-amount write-off, no
  prior-device evidence for fraud, no in-transit rule, ignores the delivery
  address).
* ``v2`` applies the whole policy.

Token usage is estimated as characters / 4 so traces carry realistic-looking
numbers for cost reporting. They are estimates, not measurements.
"""

from __future__ import annotations

import json
import re
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

from agent_runtime import Message, ToolCall, Usage

DISPUTE_ID = re.compile(r"\bdsp_[A-Za-z0-9]+\b")
SMALL_AMOUNT_MINOR = 2500
REPEAT_DISPUTES = 3


def _ts(s: str) -> datetime:
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def estimate_tokens(text: str) -> int:
    return max(1, len(text) // 4)


@dataclass
class Decision:
    decision: str
    rule: str
    confidence: float
    rationale: str
    cites: list[str]


class PolicyTriageModel:
    provider_name = "deterministic"

    def __init__(self, version: str = "v2") -> None:
        if version not in ("v1", "v2"):
            raise ValueError("version must be v1 or v2")
        self.version = version
        self.model = f"dispute-triage-heuristic-{version}"

    # -- LLM protocol --------------------------------------------------------------------
    def complete(self, messages: list[Message], tools: list[dict[str, Any]] | None = None) -> Message:
        prompt_chars = sum(len(m.content or "") for m in messages) + len(json.dumps(tools or []))
        results = _tool_results(messages)
        user = next((m.content for m in reversed(messages) if m.role == "user"), "")
        match = DISPUTE_ID.search(user)
        if not match:
            return self._final({"decision": "REQUEST_INFO", "confidence": 0.0, "policy_rule": None,
                                "rationale": "No dispute id in the request.", "citations": []}, prompt_chars)
        dispute_id = match.group(0)

        if "get_dispute" not in results:
            return self._calls([("get_dispute", {"dispute_id": dispute_id})], prompt_chars)
        d = results["get_dispute"][-1]
        if "error" in d:
            return self._final({"decision": "REQUEST_INFO", "confidence": 0.0, "policy_rule": None,
                                "rationale": f"Dispute lookup failed: {d['error']}.", "citations": []},
                               prompt_chars)
        if "get_transaction" not in results:
            calls = [
                ("get_transaction", {"transaction_id": d["transaction_id"]}),
                ("get_customer_history", {"dispute_id": dispute_id}),
                ("get_merchant_evidence", {"transaction_id": d["transaction_id"]}),
                ("search_policy", {"query": d["reason_code"], "k": 4}),
            ]
            if self.version == "v2":
                calls.append(("search_policy", {"query": "small amount write-off repeat disputes", "k": 2}))
            return self._calls(calls, prompt_chars)

        t = results["get_transaction"][-1]
        h = results.get("get_customer_history", [{}])[-1]
        ev = results.get("get_merchant_evidence", [{}])[-1].get("evidence", [])
        retrieved = {s["id"] for r in results.get("search_policy", []) for s in r.get("sections", [])}
        if any("error" in x for x in (t, h)):
            return self._final({"decision": "REQUEST_INFO", "confidence": 0.0, "policy_rule": None,
                                "rationale": "A lookup failed; cannot decide.", "citations": [d["id"]]},
                               prompt_chars)

        dec = self.decide(d, t, h, ev)
        cites = [dec.rule] if dec.rule in retrieved else []
        if dec.rule == "P3.1" and "P7.1" in retrieved and h.get("other_disputes_last_180d", 0) >= REPEAT_DISPUTES:
            cites.append("P7.1")
        out = {
            "decision": dec.decision,
            "confidence": round(dec.confidence, 2),
            "policy_rule": dec.rule,
            "rationale": dec.rationale,
            "citations": cites + dec.cites,
        }
        return self._final(out, prompt_chars)

    # -- policy as code --------------------------------------------------------------------
    def decide(self, d: dict[str, Any], t: dict[str, Any], h: dict[str, Any],
               ev: list[dict[str, Any]]) -> Decision:
        v2 = self.version == "v2"
        repeat = h.get("other_disputes_last_180d", 0) >= REPEAT_DISPUTES
        by_kind: dict[str, dict[str, Any]] = {}
        for e in ev:
            by_kind.setdefault(e["kind"], e)
        if v2 and d["amount_minor"] < SMALL_AMOUNT_MINOR and not repeat:
            return Decision("ACCEPT", "P2.1", 0.9,
                            f"Amount {d['amount_minor'] / 100:.2f} {d['currency']} is under the "
                            f"25.00 write-off threshold and the customer is not a repeat disputer.",
                            [t["id"]])
        dec = self._by_reason(d, t, h, by_kind, v2)
        if v2 and repeat:
            dec.confidence -= 0.1
            dec.rationale += (f" Customer has {h['other_disputes_last_180d']} other disputes in the last "
                              f"180 days (P7.1).")
        return dec

    def _by_reason(self, d: dict[str, Any], t: dict[str, Any], h: dict[str, Any],
                   ev: dict[str, dict[str, Any]], v2: bool) -> Decision:
        rc = d["reason_code"]
        if rc == "FRAUD_CNP":
            if t["three_ds"] in ("FRICTIONLESS", "CHALLENGE"):
                return Decision("REPRESENT", "P3.1", 0.9,
                                f"Transaction was authenticated with 3-D Secure ({t['three_ds'].lower()}), "
                                "so liability sits with the issuer.", [t["id"]])
            if v2:
                prior = [x for x in h.get("transactions", [])
                         if x["device_id"] == t["device_id"] and not x["disputed"]
                         and x["created_at"] < t["created_at"]]
                if len(prior) >= 2 and t["shipping_address"] == t["billing_address"]:
                    return Decision("REPRESENT", "P3.2", 0.75,
                                    f"Same device used for {len(prior)} earlier undisputed purchases and "
                                    "goods shipped to the billing address.",
                                    [t["id"]] + [x["id"] for x in prior[:3]])
            return Decision("ACCEPT", "P3.3", 0.7,
                            "No 3-D Secure authentication and no compelling evidence of prior use.",
                            [t["id"]])
        if rc == "ITEM_NOT_RECEIVED":
            tr = ev.get("TRACKING")
            if tr and tr["payload"].get("status") == "DELIVERED" and (
                    not v2 or tr["payload"].get("delivered_to") == t["shipping_address"]):
                return Decision("REPRESENT", "P4.1", 0.85,
                                f"Carrier tracking shows delivery on {tr['payload'].get('delivered_at', '?')[:10]}"
                                " to the order's shipping address.", [tr["id"], t["id"]])
            if v2 and tr and tr["payload"].get("status") == "IN_TRANSIT" and \
                    tr["payload"].get("expected_delivery", "") > d["opened_at"]:
                return Decision("REQUEST_INFO", "P4.2", 0.7,
                                f"Parcel is in transit; expected delivery {tr['payload']['expected_delivery'][:10]}"
                                " is after the dispute was opened. Ask the cardholder to confirm receipt.",
                                [tr["id"], d["id"]])
            cites = [tr["id"]] if tr else []
            why = ("Tracking shows delivery to a different address." if tr and
                   tr["payload"].get("status") == "DELIVERED" else "The merchant provided no proof of delivery.")
            return Decision("ACCEPT", "P4.3", 0.75, why, cites + [t["id"]])
        if rc == "NOT_AS_DESCRIBED":
            if "RETURN_RECEIVED" in ev:
                e = ev["RETURN_RECEIVED"]
                return Decision("ACCEPT", "P5.1", 0.85, "Merchant confirms the return was received.",
                                [e["id"]])
            if "PRODUCT_DESCRIPTION" in ev:
                e = ev["PRODUCT_DESCRIPTION"]
                return Decision("REPRESENT", "P5.2", 0.65,
                                "Merchant supplied the checkout description and there is no return record.",
                                [e["id"], t["id"]])
            return Decision("REQUEST_INFO", "P5.3", 0.6,
                            "Neither side gave specifics. Ask the cardholder what differed from the description.",
                            [d["id"]])
        if rc == "DUPLICATE":
            created = _ts(t["created_at"])
            near = [x for x in h.get("transactions", [])
                    if x["merchant_id"] == t["merchant_id"] and x["amount_minor"] == t["amount_minor"]
                    and abs(_ts(x["created_at"]) - created) <= timedelta(hours=24)]
            same = [x for x in near if x["order_ref"] == t["order_ref"]]
            if same:
                return Decision("ACCEPT", "P6.1", 0.9,
                                f"Order {t['order_ref']} was charged twice within 24 hours.",
                                [t["id"], same[0]["id"]])
            if near:
                return Decision("REPRESENT", "P6.2", 0.8,
                                f"The other charge is a separate order ({near[0]['order_ref']} vs {t['order_ref']}).",
                                [t["id"], near[0]["id"]])
            return Decision("REQUEST_INFO", "P6.2", 0.4,
                            "No matching second charge found. Ask the cardholder which charge is the duplicate.",
                            [t["id"]])
        if rc == "CREDIT_NOT_PROCESSED":
            if "REFUND_RECORD" in ev:
                e = ev["REFUND_RECORD"]
                return Decision("REPRESENT", "P8.1", 0.9,
                                f"Refund already issued on {e['payload'].get('refunded_at', '?')[:10]}.", [e["id"]])
            if "RETURN_RECEIVED" in ev:
                e = ev["RETURN_RECEIVED"]
                return Decision("ACCEPT", "P8.2", 0.8, "Return received and no refund on record.", [e["id"]])
            return Decision("REQUEST_INFO", "P8.3", 0.65,
                            "No refund record and no received return. Ask the merchant for its return log.",
                            [t["id"]])
        if rc == "CANCELLED_RECURRING":
            c = ev.get("CANCELLATION_RECORD")
            if c and c["payload"].get("cancelled_at", "9999") < t["created_at"]:
                return Decision("ACCEPT", "P9.1", 0.85,
                                f"Subscription was cancelled on {c['payload']['cancelled_at'][:10]}, before the charge.",
                                [c["id"], t["id"]])
            u = ev.get("USAGE_LOG")
            if not c and u and u["payload"].get("last_used_at", "") > t["created_at"]:
                return Decision("REPRESENT", "P9.2", 0.8,
                                f"No cancellation on record and {u['payload'].get('sessions_after_charge')} "
                                "sessions after the charge.", [u["id"], t["id"]])
            return Decision("REQUEST_INFO", "P9.3", 0.6,
                            "No cancellation or usage record. Ask the merchant for both.", [t["id"]])
        return Decision("REQUEST_INFO", "P1.1", 0.3, f"Unknown reason code {rc}.", [d["id"]])

    # -- message helpers -------------------------------------------------------------------
    def _calls(self, calls: list[tuple[str, dict[str, Any]]], prompt_chars: int) -> Message:
        tcs = [ToolCall(id=f"call_{uuid.uuid4().hex[:8]}", name=n, arguments=a) for n, a in calls]
        out = json.dumps([{"name": n, "arguments": a} for n, a in calls])
        return Message(role="assistant", content="", tool_calls=tcs, model=self.model,
                       usage=Usage(input_tokens=prompt_chars // 4, output_tokens=estimate_tokens(out)))

    def _final(self, payload: dict[str, Any], prompt_chars: int) -> Message:
        text = json.dumps(payload)
        return Message(role="assistant", content=text, model=self.model,
                       usage=Usage(input_tokens=prompt_chars // 4, output_tokens=estimate_tokens(text)))


def _tool_results(messages: list[Message]) -> dict[str, list[dict[str, Any]]]:
    out: dict[str, list[dict[str, Any]]] = {}
    for m in messages:
        if m.role != "tool" or not m.name:
            continue
        try:
            data = json.loads(m.content)
        except (TypeError, ValueError):
            data = {"error": "unparseable"}
        if not isinstance(data, dict):
            data = {"value": data}
        out.setdefault(m.name, []).append(data)
    return out
