import httpx
import pytest

from dispute_mcp.backend import HttpBackend, NotFound


def test_dispute_shape(backend):
    d = backend.get_dispute("dsp_0001")
    assert set(d) == {"id", "transaction_id", "customer_id", "merchant", "reason_code", "amount_minor",
                      "currency", "cardholder_message", "opened_at", "status"}
    assert d["merchant"]["name"]


def test_history_excludes_the_disputed_transaction(backend):
    d = backend.get_dispute("dsp_0001")
    h = backend.get_customer_history("dsp_0001")
    assert h["customer"]["id"] == d["customer_id"]
    assert d["transaction_id"] not in {t["id"] for t in h["transactions"]}
    assert h["transactions"] == sorted(h["transactions"], key=lambda t: (t["created_at"], t["id"]), reverse=True)


def test_repeat_disputer_has_three_recent_disputes(backend):
    import json
    from pathlib import Path
    golden = [json.loads(line) for line in
              (Path(__file__).resolve().parents[2] / "data" / "golden.jsonl").read_text().splitlines()]
    case = next(g for g in golden if g["scenario"] == "small_amount_repeat_disputer")
    h = backend.get_customer_history(case["dispute_id"])
    assert h["other_disputes_last_180d"] == 3
    assert sum(t["disputed"] for t in h["transactions"]) == 3


def test_evidence_sorted_and_unknown_txn_raises(backend):
    d = backend.get_dispute("dsp_0005")
    ev = backend.get_merchant_evidence(d["transaction_id"])
    assert [e["kind"] for e in ev] == ["TRACKING"]
    with pytest.raises(NotFound):
        backend.get_merchant_evidence("txn_nope")
    with pytest.raises(NotFound):
        backend.get_dispute("dsp_nope")


def test_http_backend_paths_and_404():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.path)
        if "missing" in request.url.path:
            return httpx.Response(404, json={"title": "Not Found"})
        if request.url.path.endswith("/evidence"):
            return httpx.Response(200, json=[])
        return httpx.Response(200, json={"id": "x"})

    client = httpx.Client(base_url="http://svc", transport=httpx.MockTransport(handler))
    b = HttpBackend("http://svc", client=client)
    b.get_dispute("d1")
    b.get_transaction("t1")
    b.get_customer_history("d1")
    assert b.get_merchant_evidence("t1") == []
    with pytest.raises(NotFound):
        b.get_dispute("missing")
    assert seen[:4] == ["/api/disputes/d1", "/api/transactions/t1",
                        "/api/disputes/d1/customer-history", "/api/transactions/t1/evidence"]


def test_http_backend_raises_on_server_error():
    client = httpx.Client(base_url="http://svc",
                          transport=httpx.MockTransport(lambda r: httpx.Response(500)))
    with pytest.raises(httpx.HTTPStatusError):
        HttpBackend("http://svc", client=client).get_transaction("t1")
