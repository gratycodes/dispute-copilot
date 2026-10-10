"""Check the live stack against the offline evals.

After `dispute-agent --pending` has triaged a freshly seeded service, every
golden case should carry the same recommendation the offline eval produced for
v2 (same model, same data, real HTTP + MCP path instead of the fixture backend).

    python scripts/live_check.py http://localhost:8080
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]


def main(base_url: str) -> int:
    offline = {}
    for line in (ROOT / "evals" / "results" / "cases.jsonl").read_text().splitlines():
        row = json.loads(line)
        if row["version"] == "v2":
            offline[row["id"]] = row
    problems = []
    with httpx.Client(base_url=base_url, timeout=10) as http:
        for dispute_id, want in sorted(offline.items()):
            recs = http.get(f"/api/disputes/{dispute_id}/recommendations").raise_for_status().json()
            if not recs:
                problems.append(f"{dispute_id}: no recommendation")
                continue
            got = recs[-1]
            if (got["decision"], got["policy_rule"]) != (want["predicted"], want["rule"]):
                problems.append(f"{dispute_id}: live {got['decision']}/{got['policy_rule']} "
                                f"vs offline {want['predicted']}/{want['rule']}")
            if not got.get("has_trace"):
                problems.append(f"{dispute_id}: no trace stored")
    for p in problems[:20]:
        print("MISMATCH:", p)
    print(f"{len(offline)} golden cases checked, {len(problems)} mismatches")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"))
