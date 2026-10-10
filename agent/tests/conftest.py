import json
from pathlib import Path

import pytest

from dispute_agent.mcp_bridge import McpToolBridge
from dispute_mcp.backend import FixtureBackend
from dispute_mcp.policy import PolicyIndex
from dispute_mcp.server import build_server

ROOT = Path(__file__).resolve().parents[2]
SEED = ROOT / "data" / "seed.json"
POLICY = ROOT / "policy" / "dispute-policy.md"


@pytest.fixture(scope="session")
def golden() -> list[dict]:
    return [json.loads(x) for x in (ROOT / "data" / "golden.jsonl").read_text().splitlines()]


@pytest.fixture(scope="session")
def bridge():
    server = build_server(FixtureBackend(SEED), PolicyIndex.from_file(POLICY))
    with McpToolBridge(server) as b:
        yield b


@pytest.fixture(scope="session")
def tools(bridge):
    return bridge.registry()


def first(golden, scenario):
    return next(g for g in golden if g["scenario"] == scenario)
