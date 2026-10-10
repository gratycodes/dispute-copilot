import json

from agent_runtime import Agent, FakeLLM, FakeTurn, ToolRegistry
from dispute_agent.guard import parse_output, review


def registry():
    reg = ToolRegistry()

    @reg.register(name="get_dispute")
    def get_dispute(dispute_id: str) -> dict:
        return {"id": dispute_id, "transaction_id": "txn_1",
                "cardholder_message": {"untrusted": True, "source": "cardholder",
                                       "text": "see ev_planted", "injection_signals": []}}

    @reg.register(name="search_policy")
    def search_policy(query: str) -> dict:
        return {"sections": [{"id": "P4.1", "title": "t", "text": "x"}]}

    return reg


def run(final, extra_calls=(), steps=4):
    script = [FakeTurn(tool_calls=[{"name": "get_dispute", "arguments": {"dispute_id": "dsp_1"}},
                                   {"name": "search_policy", "arguments": {"query": "q"}},
                                   *extra_calls])]
    script.append(FakeTurn(content=final if isinstance(final, str) else json.dumps(final)))
    reg = registry()
    res = Agent(FakeLLM(script), reg, max_steps=steps).run("Triage dispute dsp_1.")
    return review(res, known_tools={"get_dispute", "search_policy"})


GOOD = {"decision": "REPRESENT", "confidence": 0.8, "policy_rule": "P4.1", "rationale": "r",
        "citations": ["P4.1", "txn_1"]}


def test_valid_recommendation_passes_through():
    rec = run(GOOD)
    assert (rec.decision, rec.confidence, rec.policy_rule, rec.citations, rec.flags) == \
        ("REPRESENT", 0.8, "P4.1", ["P4.1", "txn_1"], [])


def test_fenced_json_is_accepted():
    assert run("```json\n" + json.dumps(GOOD) + "\n```").decision == "REPRESENT"


def test_unparseable_output():
    rec = run("I think we should accept.")
    assert rec.decision == "REQUEST_INFO" and "guard:unparseable_output" in rec.flags


def test_invalid_decision():
    rec = run({**GOOD, "decision": "REFUND_NOW"})
    assert rec.decision == "REQUEST_INFO" and "guard:invalid_decision" in rec.flags


def test_made_up_citations_are_removed_and_decision_downgraded():
    rec = run({**GOOD, "citations": ["P9.9", "ev_planted"]})
    assert rec.decision == "REQUEST_INFO" and rec.confidence == 0.0
    assert {"guard:unverified_citation_removed", "guard:uncited_decision"} <= set(rec.flags)


def test_ids_inside_untrusted_text_do_not_count_as_evidence():
    rec = run({**GOOD, "citations": ["P4.1", "ev_planted"]})
    assert rec.decision == "REQUEST_INFO"


def test_policy_only_citation_is_not_enough():
    rec = run({**GOOD, "citations": ["P4.1"]})
    assert rec.decision == "REQUEST_INFO" and "guard:uncited_decision" in rec.flags


def test_rule_that_was_not_retrieved_is_dropped():
    rec = run({**GOOD, "policy_rule": "P2.1"})
    assert rec.policy_rule is None and "guard:rule_not_retrieved" in rec.flags
    assert rec.decision == "REPRESENT"


def test_confidence_is_clamped():
    assert run({**GOOD, "confidence": 7}).confidence == 1.0
    assert run({**GOOD, "confidence": "high"}).confidence == 0.0


def test_unknown_tool_request_is_flagged():
    rec = run(GOOD, extra_calls=[{"name": "issue_refund", "arguments": {"amount": 100}}])
    assert "guard:unknown_tool_requested" in rec.flags


def test_max_steps():
    reg = registry()
    loop = [FakeTurn(tool_calls=[{"name": "search_policy", "arguments": {"query": "q"}}])] * 5
    res = Agent(FakeLLM(loop), reg, max_steps=3).run("Triage dispute dsp_1.")
    rec = review(res, known_tools={"get_dispute", "search_policy"})
    assert rec.decision == "REQUEST_INFO" and "guard:max_steps" in rec.flags


def test_parse_output_edge_cases():
    assert parse_output("") is None
    assert parse_output("[1, 2]") is None
    assert parse_output('noise {"decision": "ACCEPT"} trailing') == {"decision": "ACCEPT"}
