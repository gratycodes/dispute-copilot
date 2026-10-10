"""dispute-agent: triage disputes waiting in the disputes service.

    dispute-agent --api http://localhost:8080 --pending          # all RECEIVED disputes once
    dispute-agent --api http://localhost:8080 --watch 5          # keep polling
    dispute-agent --api http://localhost:8080 --dispute dsp_0001 --model v1

The agent talks to the disputes service only through the MCP server (started as
a subprocess over stdio). The only write it makes is posting a recommendation.
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
import time

from mcp.client.stdio import StdioServerParameters

from dispute_agent.api import DisputesApi
from dispute_agent.mcp_bridge import McpToolBridge
from dispute_agent.policy_model import PolicyTriageModel
from dispute_agent.triage import triage

log = logging.getLogger("dispute-agent")


def make_llm(name: str):
    if name in ("v1", "v2"):
        return PolicyTriageModel(name), name
    if name.startswith("openai:"):
        from agent_runtime import OpenAIAdapter
        model = name.split(":", 1)[1]
        return OpenAIAdapter(model=model), f"openai-{model}"
    raise SystemExit(f"unknown model {name!r}; use v1, v2 or openai:<model>")


def run_once(api: DisputesApi, bridge: McpToolBridge, model: str, ids: list[str] | None) -> int:
    targets = ids or [d["id"] for d in api.pending()]
    done = 0
    for dispute_id in targets:
        d = api.get(dispute_id)
        llm, version = make_llm(model)
        res = triage(dispute_id, llm, bridge.registry(), agent_version=version)
        api.submit(dispute_id, d["version"], res.payload())
        r = res.recommendation
        log.info("%s -> %s (%.2f) %s flags=%s", dispute_id, r.decision, r.confidence, r.policy_rule, r.flags)
        done += 1
    return done


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="dispute-agent", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--api", default=os.environ.get("DISPUTES_API", "http://localhost:8080"))
    p.add_argument("--policy", default=os.environ.get("POLICY_PATH"))
    p.add_argument("--model", default=os.environ.get("AGENT_MODEL", "v2"),
                   help="v1, v2 (deterministic) or openai:<model>")
    g = p.add_mutually_exclusive_group()
    g.add_argument("--dispute", action="append", help="dispute id (repeatable)")
    g.add_argument("--pending", action="store_true", help="triage every RECEIVED dispute once")
    g.add_argument("--watch", type=float, metavar="SECONDS", help="poll for RECEIVED disputes")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    mcp_args = ["-m", "dispute_mcp", "--api", args.api]
    if args.policy:
        mcp_args += ["--policy", args.policy]
    params = StdioServerParameters(command=sys.executable, args=mcp_args)
    api = DisputesApi(args.api)
    with McpToolBridge(params) as bridge:
        if args.watch:
            while True:
                try:
                    run_once(api, bridge, args.model, None)
                except Exception as exc:  # noqa: BLE001 - keep the worker alive, log and retry
                    log.warning("triage pass failed: %s", exc)
                time.sleep(args.watch)
        n = run_once(api, bridge, args.model, args.dispute)
        log.info("triaged %d dispute(s)", n)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
