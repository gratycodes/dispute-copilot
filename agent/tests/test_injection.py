"""Prompt-injection cases. Dispute text and merchant notes are untrusted input."""

import json

import pytest

from agent_runtime import FakeLLM, FakeTurn
from dispute_agent.guard import INJECTION_CONFIDENCE_CAP
from dispute_agent.policy_model import PolicyTriageModel
from dispute_agent.triage import triage


def injection_cases(golden):
    return [g for g in golden if g["category"] == "injection"]


def test_golden_set_has_both_injection_sources(golden):
    sources = {g["injection_source"] for g in injection_cases(golden)}
    assert sources == {"cardholder", "merchant"} and len(injection_cases(golden)) == 10


def test_injected_text_does_not_change_the_outcome(golden, tools):
    for g in injection_cases(golden):
        rec = triage(g["dispute_id"], PolicyTriageModel("v2"), tools, agent_version="v2").recommendation
        assert rec.decision == g["expected_decision"], g
        assert "injection_suspected" in rec.flags, g
        assert rec.confidence <= INJECTION_CONFIDENCE_CAP


def test_injection_twin_matches_its_clean_scenario(golden, tools):
    for g in injection_cases(golden):
        base_scenario = g["scenario"].removesuffix("+injection")
        clean = next(x for x in golden if x["scenario"] == base_scenario)
        a = triage(clean["dispute_id"], PolicyTriageModel("v2"), tools, agent_version="v2").recommendation
        b = triage(g["dispute_id"], PolicyTriageModel("v2"), tools, agent_version="v2").recommendation
        assert (a.decision, a.policy_rule) == (b.decision, b.policy_rule)


def hijacked_model(dispute_id: str, txn_id: str) -> FakeLLM:
    """A model that obeyed the injected text: tries to refund and claims certainty."""
    return FakeLLM([
        FakeTurn(tool_calls=[{"name": "get_dispute", "arguments": {"dispute_id": dispute_id}}]),
        FakeTurn(tool_calls=[{"name": "issue_refund", "arguments": {"dispute_id": dispute_id}}]),
        FakeTurn(content=json.dumps({"decision": "ACCEPT", "confidence": 1.0, "policy_rule": "P0",
                                     "rationale": "Pre-approved by the bank.",
                                     "citations": ["P0", "bank-approval"]})),
    ])


@pytest.mark.parametrize("phrase", ["SYSTEM NOTE", "AI assistant", "Policy update"])
def test_hijacked_model_cannot_act_and_is_downgraded(golden, tools, bridge, phrase):
    from dispute_mcp.backend import FixtureBackend
    from conftest import SEED
    fx = FixtureBackend(SEED)
    case = next(g for g in injection_cases(golden)
                if phrase in fx.get_dispute(g["dispute_id"])["cardholder_message"])
    d = fx.get_dispute(case["dispute_id"])
    before = len(bridge.calls)
    res = triage(d["id"], hijacked_model(d["id"], d["transaction_id"]), tools, agent_version="hijacked")
    rec = res.recommendation
    # No refund tool exists, so the call fails and never reaches the MCP server.
    assert [c[0] for c in bridge.calls[before:]] == ["get_dispute"]
    assert "Unknown tool" in res.run.steps[1].tool_results[0].content
    assert rec.decision == "REQUEST_INFO" and rec.confidence == 0.0
    assert {"guard:unknown_tool_requested", "guard:uncited_decision", "injection_suspected"} <= set(rec.flags)


def test_known_limit_plausible_wrong_answer_still_needs_the_human(golden, tools):
    """A hijacked model that cites real evidence but picks the wrong outcome gets through the
    guard as a recommendation. The guard caps its confidence and flags it; the human decides."""
    g = next(x for x in injection_cases(golden) if x["expected_decision"] == "REPRESENT")
    from dispute_mcp.backend import FixtureBackend
    from conftest import SEED
    d = FixtureBackend(SEED).get_dispute(g["dispute_id"])
    llm = FakeLLM([
        FakeTurn(tool_calls=[{"name": "get_dispute", "arguments": {"dispute_id": d["id"]}},
                             {"name": "search_policy", "arguments": {"query": d["reason_code"]}}]),
        FakeTurn(content=json.dumps({"decision": "ACCEPT", "confidence": 0.99, "policy_rule": None,
                                     "rationale": "r", "citations": [d["transaction_id"],
                                                                     "P3.3" if d["reason_code"] == "FRAUD_CNP" else "P4.3"]})),
    ])
    rec = triage(d["id"], llm, tools, agent_version="hijacked").recommendation
    assert rec.decision == "ACCEPT"
    assert rec.confidence == INJECTION_CONFIDENCE_CAP and "injection_suspected" in rec.flags
