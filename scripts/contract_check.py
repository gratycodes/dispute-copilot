"""Check that the disputes service serves the same read models as the fixture backend.

The MCP server can read either from the Java service (HttpBackend) or straight
from data/seed.json (FixtureBackend, used by the evals). If the two drifted,
the evals would be measuring a different world from the one the agent sees in
production. Run against a freshly seeded service:

    python scripts/contract_check.py http://localhost:8080
"""

from __future__ import annotations

import sys
from pathlib import Path

from dispute_mcp.backend import FixtureBackend, HttpBackend

ROOT = Path(__file__).resolve().parents[1]
SERVICE_ONLY = {"version", "updated_at"}  # extra fields the service adds for the console


def main(base_url: str) -> int:
    fx = FixtureBackend(ROOT / "data" / "seed.json")
    http = HttpBackend(base_url)
    problems: list[str] = []
    ids = fx.dispute_ids()
    for dispute_id in ids:
        a = fx.get_dispute(dispute_id)
        b = {k: v for k, v in http.get_dispute(dispute_id).items() if k not in SERVICE_ONLY}
        if a != b:
            problems.append(f"get_dispute({dispute_id}) differs")
        txn = a["transaction_id"]
        if fx.get_transaction(txn) != http.get_transaction(txn):
            problems.append(f"get_transaction({txn}) differs")
        if fx.get_merchant_evidence(txn) != http.get_merchant_evidence(txn):
            problems.append(f"get_merchant_evidence({txn}) differs")
        if fx.get_customer_history(dispute_id) != http.get_customer_history(dispute_id):
            problems.append(f"get_customer_history({dispute_id}) differs")
    for p in problems[:20]:
        print("MISMATCH:", p)
    print(f"checked {len(ids)} disputes x 4 read models: {len(problems)} mismatches")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"))
