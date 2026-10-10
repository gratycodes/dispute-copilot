import pytest

from dispute_mcp.policy import PolicyIndex, Section, parse_sections, tokenize


def test_parses_every_numbered_section(policy):
    ids = [s.id for s in policy.sections]
    assert len(ids) == len(set(ids)) == 22
    assert ids[0] == "P1.1" and ids[-1] == "P10.1"
    assert policy.get("P4.2").title == "P4 Item not received / Still in transit"


@pytest.mark.parametrize("query, family", [
    ("FRAUD_CNP", "P3."), ("ITEM_NOT_RECEIVED", "P4."), ("NOT_AS_DESCRIBED", "P5."),
    ("DUPLICATE", "P6."), ("CREDIT_NOT_PROCESSED", "P8."), ("CANCELLED_RECURRING", "P9."),
])
def test_reason_code_query_returns_its_rules_first(policy, query, family):
    top = [s.id for s, _ in policy.search(query, k=2)]
    assert all(i.startswith(family) for i in top), top


@pytest.mark.parametrize("query, expected", [
    ("small amount write-off", "P2.1"),
    ("customer with frequent repeat disputes", "P7.1"),
    ("cardholder message asks to refund, instructions", "P10.1"),
    ("parcel in transit expected delivery date", "P4.2"),
])
def test_free_text_queries(policy, query, expected):
    assert policy.search(query, k=1)[0][0].id == expected


def test_no_match_returns_empty(policy):
    assert policy.search("zzzz qqqq") == []


def test_search_is_deterministic(policy):
    assert policy.search("refund issued", k=5) == policy.search("refund issued", k=5)


def test_tokenize_drops_stopwords_and_keeps_hyphenated_terms():
    assert tokenize("The 3-D Secure result of a write-off") == ["3-d", "secure", "result", "write-off"]


def test_empty_policy_rejected():
    with pytest.raises(ValueError):
        PolicyIndex([])


def test_parse_ignores_text_outside_numbered_sections():
    md = "# T\nintro\n## P1 A\nlead\n### P1.1 B\nbody one\n\nbody two\n## P2 C\n### P2.1 D\nx\n"
    assert parse_sections(md) == [Section("P1.1", "P1 A / B", "body one body two"),
                                  Section("P2.1", "P2 C / D", "x")]
