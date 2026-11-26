#!/usr/bin/env bash
# Run the whole functional matrix or one case against fresh demo-v1 data.
set -euo pipefail
cd "$(dirname "$0")/../.."
source infra/scripts/lib/saga-test-stack.sh
source infra/scripts/lib/failure-assertions.sh
source infra/scripts/lib/failure-scenarios.sh

cases=(C1 C2 C3 C4 C5 C6)
if (( $# > 0 )); then
    if (( $# != 1 )) || [[ ! $1 =~ ^C[1-6]$ ]]; then
        echo "Uso: $0 [C1|C2|C3|C4|C5|C6]" >&2
        exit 2
    fi
    cases=("$1")
fi

witness() { jq -nc "$@" > "$run_dir/injection.json"; }
export SAGA_TIMEOUT_STEP=3s RECONCILIATION_POLL_INTERVAL=1s
export SAGA_RETRY_MAX_ATTEMPTS=5 SAGA_RETRY_INITIAL_DELAY=1s SAGA_RETRY_MAX_DELAY=1m
export OUTBOX_PUBLISHER_ENABLED=true
unset SETTLEMENT_SIMULATION_DENY_ORDER_ID
revision=$(git rev-parse HEAD)
init_stack matrix >&2
services=(order account custody settlement orchestrator)
for svc in "${services[@]}"; do start "$svc"; done
for svc in "${services[@]}"; do ready "$svc"; done
api="http://127.0.0.1:${ports[order]}/api/ordens"
postgres=$(sql orders 'SHOW server_version')
rabbit=$(curl -fsS --max-time 5 -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" \
    "http://127.0.0.1:$RABBITMQ_MANAGEMENT_PORT/api/overview" | jq -er .rabbitmq_version)
java_version=$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | sed -nE 's/^(openjdk|java) version "([^"]+)".*/\2/p')

atv001=0
atv002=0
check_ledger > "$run_dir/seed.json"
started=$(date -u +%FT%TZ)
for id in "${cases[@]}"; do
    echo "$id em execução" >&2
    : > "$run_dir/orders.ndjson"
    "$id"
    await_delivery
    ledger=$(check_ledger)
    jq -nc --arg id "$id" --slurpfile fault "$run_dir/injection.json" --slurpfile orders "$run_dir/orders.ndjson" \
        --argjson ledger "$ledger" '{id:$id,injection:$fault[0],orders:$orders,ledger:$ledger}' >> "$run_dir/cases.ndjson"
    echo "$id aprovado" >&2
done
jq -n --arg revision "$revision" --arg started "$started" --arg finished "$(date -u +%FT%TZ)" \
    --arg java "$java_version" --arg postgres "$postgres" --arg rabbit "$rabbit" \
    --slurpfile seed "$run_dir/seed.json" --slurpfile cases "$run_dir/cases.ndjson" \
    '{revision:$revision,startedUTC:$started,finishedUTC:$finished,seed:"demo-v1",
      runtime:{java:$java,postgresql:$postgres,rabbitmq:$rabbit},initialLedger:$seed[0],cases:$cases}'
