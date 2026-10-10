"""Small client for the disputes service: list work, submit recommendations."""

from __future__ import annotations

from typing import Any

import httpx


class DisputesApi:
    def __init__(self, base_url: str, *, client: httpx.Client | None = None) -> None:
        self._c = client or httpx.Client(base_url=base_url.rstrip("/"), timeout=10.0)

    def pending(self, limit: int = 50) -> list[dict[str, Any]]:
        r = self._c.get("/api/disputes", params={"status": "RECEIVED", "limit": limit})
        r.raise_for_status()
        return r.json()["items"]

    def get(self, dispute_id: str) -> dict[str, Any]:
        r = self._c.get(f"/api/disputes/{dispute_id}")
        r.raise_for_status()
        return r.json()

    def submit(self, dispute_id: str, version: int, payload: dict[str, Any]) -> dict[str, Any]:
        """Idempotent: the key is tied to the dispute version, so a retry after a timeout is
        replayed by the service instead of creating a second recommendation."""
        key = f"rec:{dispute_id}:v{version}:{payload['agent_version']}"
        r = self._c.post(f"/api/disputes/{dispute_id}/recommendations", json=payload,
                         headers={"Idempotency-Key": key, "X-Api-Client": "dispute-agent"})
        r.raise_for_status()
        return r.json()
