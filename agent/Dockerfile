# Build context: repository root.
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY mcp-server mcp-server
COPY agent agent
COPY policy policy
RUN pip install --no-cache-dir ./mcp-server ./agent
USER 1000
ENV POLICY_PATH=/app/policy/dispute-policy.md
CMD ["dispute-agent", "--watch", "5"]
