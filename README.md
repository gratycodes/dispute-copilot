# dispute-copilot

An agent that drafts a recommendation for each card dispute (accept, represent, or ask for more
information) with the policy sections and facts it relied on, and a reviewer console where a
person makes the actual decision. The agent has no tool that moves money or changes a dispute's
outcome; the most it can do is post a recommendation.

Everything here runs on **synthetic data**: made-up customers, merchants, transactions and a
made-up dispute policy (`policy/dispute-policy.md`). Nothing comes from a real issuer or merchant.

## The problem

Datos Insights estimates 261 million chargebacks worldwide in 2025, worth US$33.8 billion in
disputed transactions, and puts the cost of processing one dispute to completion at US$9.08 to
US$10.32 for a financial institution ([Surging Card Disputes: A Global Perspective on the State of
Chargebacks](https://datos-insights.com/wp-content/uploads/2025/03/20250311_Surging-Card-Disputes_A-Global-Perspective-on-the-State-of-Chargebacks_Report_Summary.pdf),
report summary, 2025; the study was commissioned by Ethoca, a Mastercard company).

Much of that cost is an analyst gathering the same facts for every case: the transaction, 3-D
Secure result, the customer's history, what the merchant submitted, and which policy rule applies.
That gathering is a reasonable job for an agent. The decision is not, because it moves money and
the cardholder's text is untrusted input. So this project draws the line there: the agent gathers
and proposes, a person decides, and every disagreement is kept as a label for the next eval.

## Architecture

```mermaid
flowchart LR
    subgraph console["Reviewer console (React/TS)"]
        Q[Queue] --> D[Dispute: evidence, recommendation, trace]
        D --> F[Approve / override with reason]
        L[Overrides = labels]
    end

    subgraph svc["disputes-service (Java 21, Spring Boot)"]
        API[REST API, Idempotency-Key on every POST]
        SM[State machine]
        DB[(Postgres: disputes, recommendations, decisions, events, outbox)]
        R[Outbox relay]
        API --> SM --> DB
        DB --> R
    end

    subgraph agent["dispute-agent (Python)"]
        A[agent-runtime-kit Agent] --> G[Guard: citations, injection, confidence cap]
    end

    subgraph mcp["dispute-mcp (MCP server, stdio)"]
        T1[get_dispute] & T2[get_transaction] & T3[get_customer_history] & T4[get_merchant_evidence] & T5[search_policy]
    end

    F -->|POST decision| API
    console -->|GET| API
    A -->|tool calls| mcp
    mcp -->|read-only GET| API
    G -->|POST recommendation + OTLP trace| API
    R -->|dispute-events| K[(Kafka)]
```

| Component | What it does |
|---|---|
| `disputes-service/` | Java 21, Spring Boot 4.1, Postgres via Flyway and JdbcClient. Dispute lifecycle as an explicit state machine; each change appends a dispute event and an outbox row in the same transaction; a relay publishes the outbox to Kafka (`FOR UPDATE SKIP LOCKED`, at-least-once). Events carry balanced ledger postings (hold, release to cardholder or merchant). All POSTs require an `Idempotency-Key`. |
| `mcp-server/` | MCP server with five read-only tools. Reads from the service over HTTP, or from `data/seed.json` for evals. Free text from cardholders and merchants comes back wrapped as untrusted data with any injection signals listed. Every call goes to an audit log. |
| `agent/` | Built on [agent-runtime-kit](https://github.com/gratycodes/agent-runtime-kit). Calls the MCP tools, produces a decision, policy rule, rationale, citations and confidence, runs it through a guard, and posts it with an OTel GenAI trace. |
| `console/` | React 19 + TypeScript. Queue by status, the dispute with the cardholder's text marked untrusted, cited evidence, the trace as a span timeline, and an approve/override form that requires a reason for overrides. |
| `evals/` | 76-case synthetic golden set, agreement and kappa for two agent versions, a paired v1 vs v2 comparison with [evaldelta](https://github.com/gratycodes/evaldelta), and cost from the traces with [otel-genai-cost](https://github.com/gratycodes/otel-genai-cost). |

### Dispute lifecycle

```
RECEIVED --recommend--> AWAITING_REVIEW --recommend--> AWAITING_REVIEW   (newer recommendation)
AWAITING_REVIEW --accept--> ACCEPTED
AWAITING_REVIEW --represent--> REPRESENTED --close--> CLOSED_WON | CLOSED_LOST
AWAITING_REVIEW --request info--> INFO_REQUESTED --info received--> RECEIVED
```

A recommendation can only move a dispute into `AWAITING_REVIEW`. Every other transition needs a
decision from a reviewer, so even a fully compromised agent cannot accept or represent a case.

## Demo

Screenshots from the real stack (service + Postgres + agent over MCP), synthetic data.

**Queue.** The agent has triaged all 76 open disputes. Two rows carry a prompt-injection flag.

![Queue](docs/screenshots/queue.png)

**A dispute with an injection attempt.** The cardholder's message tells the "system" to recommend
ACCEPT with confidence 1.0 and issue the refund. The agent still recommends REPRESENT under P3.1
(the transaction was 3-D Secure authenticated), the guard caps confidence at 60% and flags the case,
and the console shows the message as an untrusted claim. The trace panel shows every model call and
tool call; the OTLP JSON can be downloaded and opened in a trace viewer.

![Dispute with injection](docs/screenshots/dispute-injection.png)

**Overriding the agent.** The agent recommends REPRESENT because the merchant's description matches
and there is no return on record. The cardholder's message quotes a return tracking number, which
the deterministic agent cannot weigh. The reviewer asks for information instead, and the reason is
required.

![Override](docs/screenshots/override.png)

**Overrides become labels** (`GET /api/labels?overrides_only=true`), ready to be added to the
golden set.

![Overrides](docs/screenshots/overrides.png)

## The agent, and what "the model" is here

The agent loop, tool registry, OTel GenAI tracing and FakeLLM come from agent-runtime-kit. The model
that drives it in this repo is **not an LLM**. `PolicyTriageModel` is a deterministic stand-in that
speaks the same interface: it asks for tools in a fixed order, reads the structured facts, and
applies the policy rules in code. That makes CI and the evals reproducible without an API key, and
it makes the safety tests meaningful (the same tools, guard and state machine a real model would
face). Token counts are estimated as characters / 4.

There are two versions so the eval harness has something to compare:

- **v1** misses three rules: the small-amount write-off (P2.1) and its repeat-disputer exception
  (P7.1), the known-device rule for fraud (P3.2), and it ignores where a parcel was delivered (P4.x).
- **v2** implements them.

`--model openai:<model>` swaps in agent-runtime-kit's OpenAI adapter with the prompt in
`agent/src/dispute_agent/prompt.py`. That path is wired up but not exercised in CI and **not
evaluated**; no numbers in this README come from an LLM.

### Guard

Every recommendation passes `guard.review()` before it is posted:

- Citations must be ids that a tool actually returned (policy sections or transaction/evidence
  ids). An id that only appears inside untrusted text does not count.
- A decision needs at least one retrieved policy section and one fact. Otherwise it becomes
  REQUEST_INFO with confidence 0 and a `guard:*` flag.
- If any tool result carried injection signals, the case is flagged `injection_suspected` and
  confidence is capped at 0.6.

Known limit, kept as a test (`test_injection.py`): a hijacked model that cites real evidence but
picks the wrong outcome passes the guard, flagged and capped at 0.6. The guard checks grounding, not
judgement; the human review step is what catches that.

## Eval results (synthetic)

From `python evals/run_evals.py` on `data/golden.jsonl`. Full output in
[`evals/RESULTS.md`](evals/RESULTS.md). These numbers show the harness and the guardrails working on
cases built from the same policy the deterministic model implements. They say nothing about how an
LLM would score.

| | v1 | v2 |
|---|---|---|
| agreement with expected outcome | 60/76 (78.9%) | 73/76 (96.1%) |
| rule cases (n=63) | 81.0% | 100% |
| judgement cases (n=3) | 0% | 0% |
| injection cases (n=10) | 90.0% | 100% |
| Cohen's kappa (95% CI) | 0.664 [0.514, 0.798] | 0.938 [0.857, 1.0] |
| injection cases: outcome same as the clean twin | 100% | 100% |
| injection cases flagged / clean cases wrongly flagged | 10/10, 0 | 10/10, 0 |
| estimated cost of the 76-case run (hypothetical prices) | $0.0421 | $0.0456 |

- **v1 vs v2 (evaldelta, clustered by scenario):** +17.1 points, cluster bootstrap 95% CI
  [+4.2, +32.1]; 13 cases fixed, 0 broken. The group-level permutation test gives p = 0.0625, so
  with only 32 scenario groups the improvement is borderline by that test, and evaldelta says so.
- **The 3 judgement cases fail on purpose.** In `nad_claimed_return` the cardholder's message
  mentions a return tracking number the merchant has not matched. The right call is to ask for
  information, but that fact only exists in free text, which the deterministic model does not read.
  This is the kind of case an LLM is for, and the reason reviewers can override.
- **Cost** comes from the OTel GenAI spans priced by `otel-genai-cost` with a made-up price book
  (`evals/pricebook.yaml`) and estimated token counts. It shows the pipeline, not a real bill.

CI re-runs the evals, fails if v2 drops below 90% or any injection case changes outcome, and fails
if the committed results drift.

## Run it

### Docker

```bash
docker compose up --build
open http://localhost:5173        # console; API on http://localhost:8080
```

Starts Postgres, Kafka, the service (seeded from `data/seed.json`), the agent (polling every 5 s for
new disputes) and the console behind nginx.

### Without Docker

Needs Java 21 + Maven, Python 3.11+, Node 22, and a Postgres database. Kafka is optional; without
it the outbox relay logs events instead.

```bash
createdb disputes
DB_URL=jdbc:postgresql://localhost:5432/disputes DB_USER=$USER DB_PASSWORD= scripts/run-local.sh
```

The script builds the idempotency starter (not on Maven Central) from a pinned commit, starts the
service, runs the agent once over all open disputes and then keeps it watching, and starts the
console on http://localhost:5173.

### Evals only (no database)

```bash
python -m venv .venv && . .venv/bin/activate
pip install -e mcp-server -e agent -r evals/requirements.txt
python evals/run_evals.py          # writes evals/RESULTS.md and evals/out/traces-*.jsonl
evals/cost.sh                      # needs Go; prices the traces
```

## Tests

| Layer | Tests | Command |
|---|---|---|
| MCP server | 40 | `pytest mcp-server` |
| Agent, guard, injection | 57 | `pytest agent` |
| Java unit (state machine, ledger postings) | 28 | `mvn -f disputes-service/pom.xml test` |
| Java integration (Postgres; Kafka when `KAFKA_BOOTSTRAP_SERVERS` is set) | 16 | `mvn -f disputes-service/pom.xml verify` |
| Console unit | 16 | `cd console && npm test` |
| Console end-to-end (Playwright, mocked API from captured responses) | 4 | `cd console && npm run test:e2e` |

CI (`.github/workflows/ci.yml`) runs each component's tests on its own, then an integration job with
Postgres and Kafka service containers that also starts the real service, checks the API returns the
same data the evals use (`scripts/contract_check.py`), lets the agent triage every dispute through
MCP, checks the live recommendations match the offline eval (`scripts/live_check.py`), and waits for
the outbox to drain to Kafka. A last job builds and starts the Docker Compose stack.

## What is real and what is simplified

Real:

- The state machine, idempotent POSTs (via
  [idempotency-spring-boot-starter](https://github.com/gratycodes/idempotency-spring-boot-starter),
  JDBC store), transactional outbox and Kafka publishing, tested against real Postgres and Kafka.
- The MCP server and the agent talking to it over stdio, the guard, and the injection handling.
- OTel GenAI spans for every model and tool call, stored with the recommendation and shown in the
  console.
- The eval harness and the statistics.

Simplified or not there:

- **No LLM in the evaluated path.** See above.
- **The policy is invented** and much smaller than a real network's rules. There are no network
  reason codes, deadlines, or representment document packages.
- **Ledger postings are emitted on events but nothing consumes them.** The posting style follows my
  [payment-ledger-platform](https://github.com/gratycodes/payment-ledger-platform) project; there is
  no code shared with it.
- **Seeded disputes have no event history.** They are imported straight into their starting status,
  so their event log begins at the first recommendation.
- **Outbox delivery is at least once,** and the relay holds a database transaction open while it
  sends to Kafka. Fine for a demo, not for high volume.
- **No authentication.** The reviewer is a free-text field and the API trusts the `X-Api-Client`
  header for idempotency scoping.
- **The trace link is a download.** The console renders the spans and lets you download OTLP JSON;
  there is no hosted trace backend. The read-only tools, audit log and untrusted-text wrapping
  follow the approach in my [kafka-ops-mcp](https://github.com/gratycodes/kafka-ops-mcp) project, again as a pattern rather than shared code.
- **Injection detection is a list of regexes.** It catches the attempts in the golden set; it will
  miss paraphrases. The defence that matters is structural: no money-moving tools, a state machine
  that only lets a recommendation reach review, and a person deciding.

## License

MIT, see [LICENSE](LICENSE).
