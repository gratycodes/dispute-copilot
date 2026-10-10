#!/usr/bin/env bash
# Price the eval traces with otel-genai-cost's costreport (pinned commit).
# Run evals/run_evals.py first; it writes evals/out/traces-*.jsonl.
# Prices are hypothetical, see evals/pricebook.yaml.
set -euo pipefail
cd "$(dirname "$0")/.."
REV=629572f3da3f9d25b5b66fa462bd3f228a073aef
mkdir -p evals/results
# Run from a temp dir so `go run pkg@version` doesn't look for a go.mod here.
( cd "$(mktemp -d)" && GOTOOLCHAIN=auto go run "github.com/gratycodes/otel-genai-cost/cmd/costreport@${REV}" \
    -pricebook "$OLDPWD/evals/pricebook.yaml" -by agent.version \
    "$OLDPWD/evals/out/traces-v1.jsonl" "$OLDPWD/evals/out/traces-v2.jsonl" ) \
  | sed "s#$PWD/##" | tee evals/results/cost.txt
