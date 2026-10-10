import asyncio
import json

from mcp import Client

from dispute_mcp.server import TOOL_NAMES, AuditLog, build_server


def call(server, name, args):
    async def go():
        async with Client(server) as c:
            r = await c.call_tool(name, args)
            return r, json.loads(r.content[0].text)
    return asyncio.run(go())


def test_exposes_only_read_only_tools(backend, policy):
    async def go():
        async with Client(build_server(backend, policy)) as c:
            return [t.name for t in (await c.list_tools()).tools]
    names = asyncio.run(go())
    assert tuple(sorted(names)) == tuple(sorted(TOOL_NAMES))
    assert not [n for n in names if any(w in n for w in ("refund", "accept", "update", "close", "decide", "post"))]


def test_cardholder_message_is_wrapped(backend, policy, seed_path):
    seed = json.loads(seed_path.read_text())
    attack = next(d for d in seed["disputes"] if "SYSTEM NOTE" in d["cardholder_message"])
    _, out = call(build_server(backend, policy), "get_dispute", {"dispute_id": attack["id"]})
    msg = out["cardholder_message"]
    assert msg["untrusted"] is True and msg["source"] == "cardholder"
    assert "ignore_instructions" in msg["injection_signals"]


def test_merchant_note_is_wrapped(backend, policy, seed_path):
    seed = json.loads(seed_path.read_text())
    ev = next(e for e in seed["evidence"] if e["merchant_note"])
    _, out = call(build_server(backend, policy), "get_merchant_evidence", {"transaction_id": ev["transaction_id"]})
    notes = [e["merchant_note"] for e in out["evidence"] if e["merchant_note"]]
    assert notes and notes[0]["source"] == "merchant" and notes[0]["injection_signals"]


def test_policy_search_tool(backend, policy):
    _, out = call(build_server(backend, policy), "search_policy", {"query": "ITEM_NOT_RECEIVED", "k": 2})
    assert [s["id"][:3] for s in out["sections"]] == ["P4.", "P4."]


def test_not_found_is_data_and_audited(backend, policy, tmp_path):
    audit = AuditLog(tmp_path / "audit.jsonl")
    server = build_server(backend, policy, audit=audit)
    _, out = call(server, "get_transaction", {"transaction_id": "txn_missing"})
    assert out["error"] == "not_found"
    call(server, "get_customer_history", {"dispute_id": "dsp_0001"})
    lines = [json.loads(x) for x in (tmp_path / "audit.jsonl").read_text().splitlines()]
    assert [(e["tool"], e["ok"]) for e in lines] == [("get_transaction", False), ("get_customer_history", True)]


def test_backend_failure_does_not_leak_details(policy):
    class Broken:
        def get_dispute(self, dispute_id):
            raise RuntimeError("password=hunter2 at db-host")
    _, out = call(build_server(Broken(), policy), "get_dispute", {"dispute_id": "x"})
    assert out == {"error": "backend_error", "detail": "RuntimeError"}
