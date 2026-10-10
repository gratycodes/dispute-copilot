"""Expose MCP tools to agent-runtime-kit's synchronous agent loop.

agent-runtime-kit calls tools synchronously; the MCP client is async. The
bridge runs one event loop in a background thread, keeps a single MCP session
open, and turns every MCP tool into a ToolRegistry entry with the server's
own JSON schema.
"""

from __future__ import annotations

import asyncio
import threading
from concurrent.futures import Future
from typing import Any

from agent_runtime import ToolRegistry
from mcp import Client


class McpToolBridge:
    def __init__(self, server: Any, *, timeout: float = 30.0) -> None:
        """``server`` is anything mcp.Client accepts: an MCPServer (in-process),
        StdioServerParameters (subprocess) or a Streamable HTTP URL."""
        self._server = server
        self._timeout = timeout
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._loop.run_forever, name="mcp-bridge", daemon=True)
        self._client: Client | None = None
        self.tools: list[Any] = []
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def _run(self, coro: Any) -> Any:
        fut: Future[Any] = asyncio.run_coroutine_threadsafe(coro, self._loop)
        return fut.result(self._timeout)

    async def _session(self, ready: asyncio.Event, stop: asyncio.Event) -> None:
        # anyio requires the client to be entered and exited in the same task,
        # so one long-lived task owns the session and tool calls run beside it.
        async with Client(self._server) as client:
            self._client = client
            self.tools = list((await client.list_tools()).tools)
            ready.set()
            await stop.wait()
        self._client = None

    def __enter__(self) -> McpToolBridge:
        self._thread.start()

        async def make_events() -> tuple[asyncio.Event, asyncio.Event]:
            return asyncio.Event(), asyncio.Event()

        ready, self._stop = self._run(make_events())
        self._task = asyncio.run_coroutine_threadsafe(self._session(ready, self._stop), self._loop)
        try:
            self._run(asyncio.wait_for(ready.wait(), self._timeout))
        except BaseException:
            if self._task.done():
                self._task.result()  # surface the connection error
            raise
        return self

    def __exit__(self, *exc: Any) -> None:
        try:
            self._loop.call_soon_threadsafe(self._stop.set)
            self._task.result(self._timeout)
        finally:
            self._loop.call_soon_threadsafe(self._loop.stop)
            self._thread.join(timeout=5)
            self._loop.close()

    def call(self, name: str, arguments: dict[str, Any]) -> str:
        assert self._client is not None, "bridge not started"
        self.calls.append((name, dict(arguments)))
        result = self._run(self._client.call_tool(name, arguments))
        text = "".join(getattr(c, "text", "") for c in result.content)
        if result.is_error:
            return '{"error": "tool_error", "detail": ' + _json_str(text[:300]) + "}"
        return text

    def registry(self) -> ToolRegistry:
        reg = ToolRegistry()
        for t in self.tools:
            name = t.name

            def fn(_name: str = name, **kwargs: Any) -> str:
                return self.call(_name, kwargs)

            reg.register(fn, name=name, description=t.description or name,
                         parameters=dict(t.inputSchema if hasattr(t, "inputSchema") else t.input_schema))
        return reg


def _json_str(s: str) -> str:
    import json
    return json.dumps(s)
