import pytest
from conftest import first

from agent_runtime import Message
from dispute_agent.policy_model import PolicyTriageModel
from dispute_agent.triage import triage

RULE_SCENARIOS = [
    "fraud_3ds", "fraud_no_signals", "fraud_prior_device", "fraud_prior_device_ship_mismatch",
    "inr_delivered", "inr_delivered_elsewhere", "inr_in_transit", "inr_no_tracking",
    "nad_return_received", "nad_description_no_return", "nad_nothing",
    "dup_same_order", "dup_different_orders", "cnp_refund_issued", "cnp_return_no_refund",
    "cnp_nothing", "recurring_cancelled", "recurring_used", "recurring_nothing",
    "small_amount", "small_amount_repeat_disputer",
]


@pytest.mark.parametrize("scenario", RULE_SCENARIOS)
def test_v2_applies_each_rule(golden, tools, scenario):
    g = first(golden, scenario)
    rec = triage(g["dispute_id"], PolicyTriageModel("v2"), tools, agent_version="v2").recommendation
    assert (rec.decision, rec.policy_rule) == (g["expected_decision"], g["expected_rule"])
    assert g["expected_rule"] in rec.citations
    assert rec.flags == [] and 0.5 <= rec.confidence <= 0.9


@pytest.mark.parametrize("scenario, v1_decision", [
    ("fraud_prior_device", "ACCEPT"), ("inr_delivered_elsewhere", "REPRESENT"),
    ("inr_in_transit", "ACCEPT"), ("small_amount", "REPRESENT"),
])
def test_v1_misses_the_rules_it_does_not_know(golden, tools, scenario, v1_decision):
    g = first(golden, scenario)
    rec = triage(g["dispute_id"], PolicyTriageModel("v1"), tools, agent_version="v1").recommendation
    assert rec.decision == v1_decision != g["expected_decision"]


def test_judgement_case_is_a_known_miss(golden, tools):
    # The stand-in never reads free text, so a cardholder's claimed return is invisible to it.
    g = first(golden, "nad_claimed_return")
    rec = triage(g["dispute_id"], PolicyTriageModel("v2"), tools, agent_version="v2").recommendation
    assert g["expected_decision"] == "REQUEST_INFO" and rec.decision == "REPRESENT"


def test_repeat_disputer_lowers_confidence_and_cites_p7(golden, tools):
    g = first(golden, "small_amount_repeat_disputer")
    rec = triage(g["dispute_id"], PolicyTriageModel("v2"), tools, agent_version="v2").recommendation
    assert "P7.1" in rec.citations and rec.confidence == 0.8


def test_tool_plan_and_usage(golden, tools):
    res = triage("dsp_0001", PolicyTriageModel("v2"), tools, agent_version="v2")
    names = [tc.name for tc in res.run.tool_calls]
    assert names == ["get_dispute", "get_transaction", "get_customer_history", "get_merchant_evidence",
                     "search_policy", "search_policy"]
    assert res.run.step_count == 3
    assert res.run.usage.input_tokens > 500 and res.run.usage.output_tokens > 20


def test_unknown_dispute_yields_request_info(tools):
    rec = triage("dsp_9999", PolicyTriageModel("v2"), tools, agent_version="v2").recommendation
    assert rec.decision == "REQUEST_INFO" and rec.confidence == 0.0


def test_no_dispute_id_in_prompt():
    msg = PolicyTriageModel().complete([Message(role="user", content="hello")])
    assert '"REQUEST_INFO"' in msg.content and not msg.tool_calls


def test_bad_version():
    with pytest.raises(ValueError):
        PolicyTriageModel("v3")
