from pathlib import Path

import pytest

from dispute_mcp.backend import FixtureBackend
from dispute_mcp.policy import PolicyIndex

ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture(scope="session")
def seed_path() -> Path:
    return ROOT / "data" / "seed.json"


@pytest.fixture(scope="session")
def backend(seed_path: Path) -> FixtureBackend:
    return FixtureBackend(seed_path)


@pytest.fixture(scope="session")
def policy() -> PolicyIndex:
    return PolicyIndex.from_file(ROOT / "policy" / "dispute-policy.md")
