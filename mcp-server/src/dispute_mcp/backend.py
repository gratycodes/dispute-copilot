"""Where the tools get their data: the disputes service over HTTP, or the seed file.

Both backends return the same JSON shapes as the disputes service REST API, so
the fixture backend doubles as a contract for the Java service (the integration
job in CI compares them).
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Protocol

import httpx


class NotFound(LookupError):
    pass


class DisputeBackend(Protocol):
    def get_dispute(self, dispute_id: str) -> dict[str, Any]: ...

    def get_transaction(self, transaction_id: str) -> dict[str, Any]: ...

    def get_customer_history(self, dispute_id: str) -> dict[str, Any]: ...

    def get_merchant_evidence(self, transaction_id: str) -> list[dict[str, Any]]: ...


def _ts(s: str) -> datetime:
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


TXN_FIELDS = ("id", "customer_id", "merchant_id", "amount_minor", "currency", "created_at",
              "three_ds", "device_id", "billing_address", "shipping_address", "order_ref", "recurring")


class FixtureBackend:
    """Serves data straight from data/seed.json. Used by evals and unit tests."""

    def __init__(self, seed_path: str | Path) -> None:
        seed = json.loads(Path(seed_path).read_text())
        self._merchants = {m["id"]: m for m in seed["merchants"]}
        self._customers = {c["id"]: c for c in seed["customers"]}
        self._txns = {t["id"]: t for t in seed["transactions"]}
        self._disputes = {d["id"]: d for d in seed["disputes"]}
        self._evidence: dict[str, list[dict[str, Any]]] = {}
        for e in seed["evidence"]:
            self._evidence.setdefault(e["transaction_id"], []).append(e)
        self._disputes_by_txn: dict[str, list[dict[str, Any]]] = {}
        for d in seed["disputes"]:
            self._disputes_by_txn.setdefault(d["transaction_id"], []).append(d)

    def dispute_ids(self) -> list[str]:
        return list(self._disputes)

    def get_dispute(self, dispute_id: str) -> dict[str, Any]:
        d = self._disputes.get(dispute_id)
        if d is None:
            raise NotFound(f"dispute {dispute_id} not found")
        t = self._txns[d["transaction_id"]]
        m = self._merchants[t["merchant_id"]]
        return {
            "id": d["id"],
            "transaction_id": d["transaction_id"],
            "customer_id": t["customer_id"],
            "merchant": {"id": m["id"], "name": m["name"], "mcc": m["mcc"]},
            "reason_code": d["reason_code"],
            "amount_minor": d["amount_minor"],
            "currency": d["currency"],
            "cardholder_message": d["cardholder_message"],
            "opened_at": d["opened_at"],
            "status": d["status"],
        }

    def get_transaction(self, transaction_id: str) -> dict[str, Any]:
        t = self._txns.get(transaction_id)
        if t is None:
            raise NotFound(f"transaction {transaction_id} not found")
        return {k: t[k] for k in TXN_FIELDS}

    def get_customer_history(self, dispute_id: str) -> dict[str, Any]:
        d = self._disputes.get(dispute_id)
        if d is None:
            raise NotFound(f"dispute {dispute_id} not found")
        disputed = self._txns[d["transaction_id"]]
        cust = self._customers[disputed["customer_id"]]
        others = sorted(
            (t for t in self._txns.values()
             if t["customer_id"] == cust["id"] and t["id"] != disputed["id"]),
            key=lambda t: (t["created_at"], t["id"]), reverse=True,
        )
        window_start = _ts(d["opened_at"]) - timedelta(days=180)
        other_disputes = sorted(
            (od for t in others for od in self._disputes_by_txn.get(t["id"], [])),
            key=lambda od: (od["opened_at"], od["id"]), reverse=True,
        )
        return {
            "customer": {"id": cust["id"], "name": cust["name"], "country": cust["country"],
                         "created_at": cust["created_at"]},
            "transactions": [
                {**{k: t[k] for k in TXN_FIELDS}, "disputed": t["id"] in self._disputes_by_txn}
                for t in others
            ],
            "other_disputes": [
                {"id": od["id"], "transaction_id": od["transaction_id"],
                 "reason_code": od["reason_code"], "opened_at": od["opened_at"], "status": od["status"]}
                for od in other_disputes
            ],
            "other_disputes_last_180d": sum(
                1 for od in other_disputes if _ts(od["opened_at"]) >= window_start),
        }

    def get_merchant_evidence(self, transaction_id: str) -> list[dict[str, Any]]:
        if transaction_id not in self._txns:
            raise NotFound(f"transaction {transaction_id} not found")
        return [
            {k: e[k] for k in ("id", "transaction_id", "kind", "payload", "merchant_note", "submitted_at")}
            for e in sorted(self._evidence.get(transaction_id, []), key=lambda e: e["id"])
        ]


class HttpBackend:
    """Calls the disputes service REST API (read-only GETs)."""

    def __init__(self, base_url: str, *, client: httpx.Client | None = None, timeout: float = 5.0) -> None:
        self._client = client or httpx.Client(base_url=base_url.rstrip("/"), timeout=timeout)

    def _get(self, path: str) -> Any:
        r = self._client.get(path, headers={"Accept": "application/json"})
        if r.status_code == 404:
            raise NotFound(path)
        r.raise_for_status()
        return r.json()

    def get_dispute(self, dispute_id: str) -> dict[str, Any]:
        return self._get(f"/api/disputes/{dispute_id}")

    def get_transaction(self, transaction_id: str) -> dict[str, Any]:
        return self._get(f"/api/transactions/{transaction_id}")

    def get_customer_history(self, dispute_id: str) -> dict[str, Any]:
        return self._get(f"/api/disputes/{dispute_id}/customer-history")

    def get_merchant_evidence(self, transaction_id: str) -> list[dict[str, Any]]:
        return self._get(f"/api/transactions/{transaction_id}/evidence")
