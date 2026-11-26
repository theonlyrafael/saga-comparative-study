#!/usr/bin/env bash
# Run the whole functional matrix or one case against fresh demo-v1 data.
set -euo pipefail
cd "$(dirname "$0")/../.."
source infra/scripts/lib/saga-test-stack.sh
source infra/scripts/lib/failure-assertions.sh
source infra/scripts/lib/failure-scenarios.sh

cases=(C1 C2 C3)
if (( $# > 0 )); then
    if (( $# != 1 )) || [[ ! $1 =~ ^C[1-3]$ ]]; then
        echo "Uso: $0 [C1|C2|C3]" >&2
        exit 2
    fi
    cases=("$1")
fi

witness() { jq -nc "$@" > "$run_dir/injection.json"; }
export SAGA_TIMEOUT_STEP=3s RECONCILIATION_POLL_INTERVAL=1s
export SAGA_RETRY_MAX_ATTEMPTS=5 SAGA_RETRY_INITIAL_DELAY=1s SAGA_RETRY_MAX_DELAY=1m
export OUTBOX_PUBLISHER_ENABLED=true
unset SETTLEMENT_SIMULATION_DENY_ORDER_ID
init_stack matrix >&2
services=(order account custody settlement orchestrator)
for svc in "${services[@]}"; do start "$svc"; done
for svc in "${services[@]}"; do ready "$svc"; done
api="http://127.0.0.1:${ports[order]}/api/ordens"

atv001=0
atv002=0
check_ledger > "$run_dir/seed.json"
started=$(date -u +%FT%TZ)
for id in "${cases[@]}"; do
    echo "$id em execução" >&2
    : > "$run_dir/orders.ndjson"
    "$id"
    ledger=$(check_ledger)
    jq -nc --arg id "$id" --slurpfile fault "$run_dir/injection.json" --slurpfile orders "$run_dir/orders.ndjson" \
        --argjson ledger "$ledger" '{id:$id,injection:$fault[0],orders:$orders,ledger:$ledger}' >> "$run_dir/cases.ndjson"
    echo "$id aprovado" >&2
done
jq -n --arg revision "$(git rev-parse HEAD)" --arg started "$started" --arg finished "$(date -u +%FT%TZ)" \
    --slurpfile seed "$run_dir/seed.json" --slurpfile cases "$run_dir/cases.ndjson" \
    '{revision:$revision,startedUTC:$started,finishedUTC:$finished,seed:"demo-v1",initialLedger:$seed[0],cases:$cases}'
