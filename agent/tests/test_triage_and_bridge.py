import json
import sys

import httpx
from mcp.client.stdio import StdioServerParameters

from conftest import POLICY, SEED
from dispute_agent.api import DisputesApi
from dispute_agent.cli import make_llm
from dispute_agent.mcp_bridge import McpToolBridge
from dispute_agent.policy_model import PolicyTriageModel
from dispute_agent.triage import FEATURE, triage


def spans(trace):
    return [s for rs in trace["resourceSpans"] for ss in rs["scopeSpans"] for s in ss["spans"]]


def test_registry_mirrors_mcp_tools(tools):
    specs = {s["name"]: s for s in tools.list_specs()}
    assert set(specs) == {"get_dispute", "get_transaction", "get_customer_history",
                          "get_merchant_evidence", "search_policy"}
    assert specs["get_dispute"]["parameters"]["required"] == ["dispute_id"]


def test_bridge_maps_tool_errors(bridge):
    out = json.loads(bridge.call("search_policy", {"k": 2}))
    assert out["error"] == "tool_error"


def test_trace_has_genai_spans_under_one_root(tools):
    res = triage("dsp_0005", PolicyTriageModel("v2"), tools, agent_version="v2")
    ss = spans(res.trace_otlp)
    names = [s["name"] for s in ss]
    assert names.count("chat dispute-triage-heuristic-v2") == 3
    assert "invoke_agent dispute-triage" in names and "guard recommendation" in names
    assert sum(n.startswith("execute_tool ") for n in names) == 6
    assert {s["traceId"] for s in ss} == {res.trace_id}
    root = next(s for s in ss if s["name"].startswith("triage_dispute"))
    assert "parentSpanId" not in root or not root["parentSpanId"]
    attrs = {a["key"]: a["value"] for a in root["attributes"]}
    assert attrs["feature"]["stringValue"] == FEATURE
    assert attrs["dispute.recommendation"]["stringValue"] == "REPRESENT"


def test_payload_for_service(tools):
    p = triage("dsp_0005", PolicyTriageModel("v2"), tools, agent_version="v2").payload()
    assert set(p) >= {"agent_version", "decision", "confidence", "policy_rule", "rationale",
                      "citations", "flags", "trace_id", "trace", "usage", "model"}
    assert p["model"] == "dispute-triage-heuristic-v2"


def test_stdio_transport_end_to_end():
    params = StdioServerParameters(command=sys.executable,
                                   args=["-m", "dispute_mcp", "--fixture", str(SEED), "--policy", str(POLICY)])
    with McpToolBridge(params, timeout=60) as b:
        rec = triage("dsp_0001", PolicyTriageModel("v2"), b.registry(), agent_version="v2").recommendation
    assert rec.decision == "REPRESENT"


def test_api_client_uses_versioned_idempotency_key():
    seen = {}

    def handler(req: httpx.Request) -> httpx.Response:
        if req.method == "GET":
            return httpx.Response(200, json={"items": [{"id": "dsp_1"}]})
        seen["key"] = req.headers["Idempotency-Key"]
        seen["body"] = json.loads(req.content)
        return httpx.Response(201, json={"id": "rec_1"})

    api = DisputesApi("http://svc", client=httpx.Client(base_url="http://svc",
                                                        transport=httpx.MockTransport(handler)))
    assert api.pending() == [{"id": "dsp_1"}]
    api.submit("dsp_1", 3, {"agent_version": "v2", "decision": "ACCEPT"})
    assert seen["key"] == "rec:dsp_1:v3:v2" and seen["body"]["decision"] == "ACCEPT"


def test_make_llm():
    llm, version = make_llm("v1")
    assert isinstance(llm, PolicyTriageModel) and version == "v1"
