"""Validate the agent's final answer before it is stored as a recommendation.

The guard does not trust the model. It parses the output, keeps only
citations that point at ids the tools actually returned, requires at least one
retrieved policy section and one fact, records attempts to use tools that do
not exist, and caps confidence when any tool result carried injection
signals. Anything it cannot verify becomes REQUEST_INFO with confidence 0, so
the worst a confused or hijacked model can do is send a case to a human with
a flag on it, which is where every case goes anyway.
"""

from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass, field
from typing import Any

from agent_runtime import RunResult

DECISIONS = ("ACCEPT", "REPRESENT", "REQUEST_INFO")
INJECTION_CONFIDENCE_CAP = 0.6
_POLICY_ID = re.compile(r"^P\d+\.\d+$")
_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.M)


@dataclass
class Recommendation:
    decision: str
    confidence: float
    policy_rule: str | None
    rationale: str
    citations: list[str]
    flags: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class Evidence:
    """What the tools returned during the run: the only things a citation may point at."""

    fact_ids: set[str] = field(default_factory=set)
    policy_ids: set[str] = field(default_factory=set)
    injection_signals: set[str] = field(default_factory=set)
    unknown_tools: list[str] = field(default_factory=list)


def collect_evidence(run: RunResult, known_tools: set[str]) -> Evidence:
    ev = Evidence()
    for tc in run.tool_calls:
        if tc.name not in known_tools:
            ev.unknown_tools.append(tc.name)
    for step in run.steps:
        for msg in step.tool_results:
            try:
                data = json.loads(msg.content)
            except (TypeError, ValueError):
                continue
            if msg.name == "search_policy" and isinstance(data, dict):
                ev.policy_ids.update(s["id"] for s in data.get("sections", []) if "id" in s)
            else:
                _walk(data, ev)
    return ev


def _walk(node: Any, ev: Evidence) -> None:
    if isinstance(node, dict):
        if node.get("untrusted") is True:
            ev.injection_signals.update(node.get("injection_signals", []))
            return  # never harvest ids from untrusted text
        if isinstance(node.get("id"), str):
            ev.fact_ids.add(node["id"])
        for key in ("transaction_id",):
            if isinstance(node.get(key), str):
                ev.fact_ids.add(node[key])
        for v in node.values():
            _walk(v, ev)
    elif isinstance(node, list):
        for v in node:
            _walk(v, ev)


def parse_output(text: str) -> dict[str, Any] | None:
    cleaned = _FENCE.sub("", (text or "").strip())
    start, end = cleaned.find("{"), cleaned.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        data = json.loads(cleaned[start : end + 1])
    except ValueError:
        return None
    return data if isinstance(data, dict) else None


def review(run: RunResult, known_tools: set[str]) -> Recommendation:
    ev = collect_evidence(run, known_tools)
    flags: list[str] = []
    if ev.unknown_tools:
        flags.append("guard:unknown_tool_requested")
    if ev.injection_signals:
        flags.append("injection_suspected")

    def fallback(reason: str, rationale: str) -> Recommendation:
        return Recommendation("REQUEST_INFO", 0.0, None, rationale, [], flags + [reason])

    if run.stopped_reason == "max_steps":
        return fallback("guard:max_steps", "The agent did not finish within its step limit.")
    out = parse_output(run.final_text)
    if out is None:
        return fallback("guard:unparseable_output", "The agent's answer was not valid JSON.")
    decision = str(out.get("decision", "")).upper()
    if decision not in DECISIONS:
        return fallback("guard:invalid_decision", f"Unknown decision {decision!r}.")

    raw_cites = [str(c) for c in out.get("citations", []) if isinstance(c, (str, int))]
    cites = [c for c in raw_cites if c in ev.fact_ids or c in ev.policy_ids]
    if len(cites) < len(raw_cites):
        flags.append("guard:unverified_citation_removed")
    rule = out.get("policy_rule")
    rule = rule if isinstance(rule, str) and _POLICY_ID.match(rule) else None
    if rule and rule not in ev.policy_ids:
        flags.append("guard:rule_not_retrieved")
        rule = None
    has_policy = any(c in ev.policy_ids for c in cites)
    has_fact = any(c in ev.fact_ids for c in cites)
    if not (has_policy and has_fact):
        return Recommendation("REQUEST_INFO", 0.0, rule,
                              "Recommendation was not backed by a retrieved policy section and a fact "
                              f"(model said {decision}).", cites, flags + ["guard:uncited_decision"])

    try:
        confidence = float(out.get("confidence", 0.0))
    except (TypeError, ValueError):
        confidence = 0.0
    confidence = min(max(confidence, 0.0), 1.0)
    if ev.injection_signals:
        confidence = min(confidence, INJECTION_CONFIDENCE_CAP)
    rationale = str(out.get("rationale", ""))[:1000]
    return Recommendation(decision, round(confidence, 2), rule, rationale, cites, flags)
