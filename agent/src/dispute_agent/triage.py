"""Run one dispute through the agent, guard the answer, and capture its trace."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from agent_runtime import LLM, Agent, RunResult, ToolRegistry
from agent_runtime.otlp_json import spans_to_otlp_json
from agent_runtime.tracing import GenAITracing
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from dispute_agent.guard import Recommendation, review
from dispute_agent.prompt import SYSTEM_PROMPT

FEATURE = "dispute-triage"


@dataclass
class TriageResult:
    dispute_id: str
    agent_version: str
    recommendation: Recommendation
    run: RunResult
    trace_id: str
    trace_otlp: dict[str, Any]

    def payload(self) -> dict[str, Any]:
        """Body for POST /api/disputes/{id}/recommendations."""
        return {
            "agent_version": self.agent_version,
            **self.recommendation.to_dict(),
            "trace_id": self.trace_id,
            "trace": self.trace_otlp,
            "usage": self.run.usage.to_dict(),
            "model": getattr(self.run.steps[-1].assistant_message, "model", None) if self.run.steps else None,
        }


def triage(dispute_id: str, llm: LLM, tools: ToolRegistry, *, agent_version: str,
           max_steps: int = 6, capture_tool_content: bool = False) -> TriageResult:
    exporter = InMemorySpanExporter()
    provider = TracerProvider(resource=Resource.create({"service.name": "dispute-agent"}))
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    attrs = {"feature": FEATURE, "dispute.id": dispute_id, "agent.version": agent_version}
    tracing = GenAITracing(provider, attributes=attrs, capture_tool_content=capture_tool_content)
    tracer = provider.get_tracer("dispute_agent")

    with tracer.start_as_current_span(f"triage_dispute {dispute_id}", attributes=attrs) as root:
        agent = Agent(llm, tools, system_prompt=SYSTEM_PROMPT, max_steps=max_steps,
                      name="dispute-triage", tracing=tracing)
        run = agent.run(f"Triage dispute {dispute_id}.")
        with tracer.start_as_current_span("guard recommendation", attributes=attrs) as gspan:
            rec = review(run, known_tools={s["name"] for s in tools.list_specs()})
            gspan.set_attribute("guard.flags", rec.flags)
            gspan.set_attribute("guard.decision", rec.decision)
        root.set_attribute("dispute.recommendation", rec.decision)
        root.set_attribute("dispute.confidence", rec.confidence)
        trace_id = format(root.get_span_context().trace_id, "032x")

    provider.force_flush()
    trace = json.loads(spans_to_otlp_json(exporter.get_finished_spans(), indent=None))
    provider.shutdown()
    return TriageResult(dispute_id, agent_version, rec, run, trace_id, trace)
