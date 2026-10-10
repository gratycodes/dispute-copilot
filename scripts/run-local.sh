#!/usr/bin/env bash
# Run the whole stack without Docker.
#
# Needs: Java 21 + Maven, Python 3.11+, Node 22, git, and a Postgres you can reach.
# Kafka is optional: by default the outbox relay logs events instead of sending them.
#
#   createdb disputes            # or point DB_URL at any empty database
#   DB_URL=jdbc:postgresql://localhost:5432/disputes DB_USER=me DB_PASSWORD=secret scripts/run-local.sh
#
# Then open http://localhost:5173. Ctrl-C stops everything.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD

export DB_URL=${DB_URL:-jdbc:postgresql://localhost:5432/disputes}
export DB_USER=${DB_USER:-disputes}
export DB_PASSWORD=${DB_PASSWORD:-disputes}
export PORT=${PORT:-8080}
export OUTBOX_PUBLISHER=${OUTBOX_PUBLISHER:-log}   # set to kafka with KAFKA_BOOTSTRAP_SERVERS
export SEED_PATH=$ROOT/data/seed.json
API=http://localhost:$PORT
STARTER_REV=050b9a596da6b59dac0de0f78f3a29ec7abc017d

echo "== python: mcp-server + agent"
[ -d .venv ] || python3 -m venv .venv
.venv/bin/pip install -q -e mcp-server -e agent

echo "== java: idempotency starter (not on Maven Central) + disputes-service"
if ! ls ~/.m2/repository/io/github/gratycodes/idempotency-spring-boot-starter/0.1.0-SNAPSHOT/*.jar >/dev/null 2>&1; then
  mkdir -p .deps
  [ -d .deps/idempotency ] || git clone -q https://github.com/gratycodes/idempotency-spring-boot-starter.git .deps/idempotency
  git -C .deps/idempotency checkout -q "$STARTER_REV"
  mvn -B -ntp -q -f .deps/idempotency/pom.xml install -DskipTests -pl idempotency-spring-boot-starter -am
fi
mvn -B -ntp -q -f disputes-service/pom.xml package -DskipTests

pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

echo "== starting disputes-service on $API (log: disputes-service.log)"
java -jar disputes-service/target/disputes-service-0.1.0-SNAPSHOT.jar > disputes-service.log 2>&1 &
pids+=($!)
for _ in $(seq 1 60); do
  curl -sf "$API/actuator/health" >/dev/null && break
  sleep 1
done
curl -sf "$API/actuator/health" >/dev/null || { echo "service did not start; see disputes-service.log"; exit 1; }

echo "== agent: triaging RECEIVED disputes, then watching for new ones (log: agent.log)"
.venv/bin/dispute-agent --api "$API" --pending
.venv/bin/dispute-agent --api "$API" --watch 5 > agent.log 2>&1 &
pids+=($!)

echo "== console on http://localhost:5173"
cd console
[ -d node_modules ] || npm ci --no-audit --no-fund
API_URL=$API npx vite --port 5173 --strictPort
