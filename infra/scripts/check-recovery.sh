#!/usr/bin/env bash
# Exercise both sides of the settlement decision with private volumes, broker and JVMs.
set -euo pipefail
cd "$(dirname "$0")/../.."
umask 077
run_dir=$(mktemp -d)
cp "${SAGA_ENV_FILE:-infra/.env}" "$run_dir/services.env"
export SAGA_ENV_FILE="$run_dir/services.env"
project="saga-recovery-${run_dir##*.}"
compose=(docker compose -p "${project,,}" --env-file "$SAGA_ENV_FILE" -f infra/compose.yaml)
declare -A pids ports
stop() {
    local svc=$1
    kill "-${2:-TERM}" "${pids[$svc]}"
    wait "${pids[$svc]}" 2>/dev/null || true
    unset 'pids[$svc]'
}
cleanup() {
    local status=$?
    for svc in "${!pids[@]}"; do stop "$svc" || true; done
    "${compose[@]}" down -v >/dev/null
    if (( status == 0 )); then rm -r -- "$run_dir"
    else rm -f -- "$SAGA_ENV_FILE"; echo "Logs: $run_dir" >&2; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM
POSTGRES_PORT=0 RABBITMQ_PORT=0 RABBITMQ_MANAGEMENT_PORT=0 "${compose[@]}" up -d --wait
port() { "${compose[@]}" port "$1" "$2" | cut -d: -f2; }
{
    echo
    echo "POSTGRES_PORT=$(port postgres 5432)"
    echo "RABBITMQ_PORT=$(port rabbitmq 5672)"
    echo "RABBITMQ_MANAGEMENT_PORT=$(port rabbitmq 15672)"
    echo "DB_HOST=127.0.0.1"
    echo "RABBITMQ_HOST=127.0.0.1"
} >> "$SAGA_ENV_FILE"
source "$SAGA_ENV_FILE"
export RABBITMQ_USER RABBITMQ_PASSWORD RABBITMQ_VHOST RABBITMQ_MANAGEMENT_PORT
python3 infra/rabbitmq/configure.py

start() {
    local svc=$1 profile=default
    case "$svc" in order|custody|account) profile=demo;; esac
    SPRING_PROFILES_ACTIVE="$profile" SERVER_PORT=0 bash infra/scripts/run-service.sh "$svc" >"$run_dir/$svc.log" 2>&1 &
    pids[$svc]=$!
}
ready() {
    local svc=$1 deadline=$((SECONDS + 150))
    while (( SECONDS < deadline )) && kill -0 "${pids[$svc]}" 2>/dev/null; do
        ports[$svc]=$(sed -nE 's/.*Tomcat started on port ([0-9]+).*/\1/p' "$run_dir/$svc.log" | tail -1)
        if [[ -n ${ports[$svc]} ]] && curl -fsS --max-time 3 "http://127.0.0.1:${ports[$svc]}/actuator/health/readiness" >/dev/null 2>&1; then return; fi
        sleep 1
    done
    echo "Readiness failed for $svc" >&2; return 1
}
sql() {
    "${compose[@]}" exec -T postgres psql -XAtq -v ON_ERROR_STOP=1 -U "${1}_user" -d "${1}_db" -c "$2"
}
await_sql() {
    local deadline=$((SECONDS + 90)) value
    while (( SECONDS < deadline )); do
        value=$(sql "$1" "$2")
        if [[ $value == "$3" ]]; then return; fi
        sleep 1
    done
    echo "$1: expected '$3', got '$value' for $2" >&2; return 1
}
buy() {
    local receipt
    receipt=$(curl -fsS --max-time 10 -H "Idempotency-Key: $1" -H 'Content-Type: application/json' \
        -d '{"clienteId":"CLI001","ativoId":"ATV002","quantidade":1}' "$api")
    order=$(jq -er '.orderId' <<<"$receipt")
    saga=$(jq -er '.sagaId' <<<"$receipt")
    await_sql orchestrator "SELECT state FROM saga_instances WHERE saga_id='$saga'" CONFIRMANDO_LIQUIDACAO
}

check_observability() {
    local outcome=$1 metrics trace deadline timer
    await_sql orchestrator "SELECT latency_seconds>0 AND convergence_seconds>=0
        AND (compensation_seconds IS NOT NULL)=('$outcome'='REJEITADA')
        FROM saga_measurements WHERE saga_id='$saga'" t
    metrics=$(curl -fsS "http://127.0.0.1:${ports[orchestrator]}/actuator/prometheus")
    for timer in order_latency convergence_duration; do
        grep -Eq "^saga_${timer}_seconds_count\{[^}]*outcome=\"$outcome\"[^}]*\} 1(\.0)?$" <<<"$metrics"
    done
    if [[ $outcome == REJEITADA ]]; then
        grep -Eq '^saga_compensation_duration_seconds_count.* 1(\.0)?$' <<<"$metrics"
    fi
    if [[ -z ${SAGA_TRACE_QUERY_URL:-} ]]; then return 0; fi
    trace=$(sql orders "SELECT split_part(traceparent,'-',2) FROM outbox_messages
        WHERE order_id='$order' AND message_type='OrdemRecebida'")
    [[ $trace =~ ^[0-9a-f]{32}$ ]]
    jq -Rse --arg trace "$trace" --arg saga "$saga" --arg order "$order" '
        split("\n") | any(.[]; try (fromjson | .traceId == $trace and .sagaId == $saga
        and .orderId == $order and (.operationId | type == "string")) catch false)
        ' "$run_dir/orchestrator.log" >/dev/null
    deadline=$((SECONDS + 60))
    while (( SECONDS < deadline )); do
        if curl -fsS "$SAGA_TRACE_QUERY_URL/api/traces/$trace" 2>/dev/null | jq -e --arg saga "$saga" '
            .data[0] as $t | [$t.spans[] | select(any(.tags[]?; .key=="saga.id" and .value==$saga))
            | .processID as $id | $t.processes[$id].serviceName] | unique | sort ==
            ["account-service","custody-service","orchestrator-service","order-service","settlement-service"]' >/dev/null; then
            echo "Trace $trace verificado nos cinco serviços para $outcome."
            return
        fi
        sleep 1
    done
    echo "Trace incompleto para $saga ($trace)" >&2; return 1
}

# Keep the initial coordinator quiet so the lost reply can only be recovered after restart.
export RECONCILIATION_POLL_INTERVAL=1h
for svc in order account custody orchestrator; do start "$svc"; done
for svc in order account custody orchestrator; do ready "$svc"; done
api="http://127.0.0.1:${ports[order]}/api/ordens"
buy recusa-liquidacao
export SETTLEMENT_SIMULATION_DENY_ORDER_ID="$order"
start settlement; ready settlement
await_sql orchestrator "SELECT state FROM saga_instances WHERE saga_id='$saga'" REJEITADA
curl -fsS "$api/$order" | jq -e '.estado=="REJEITADA" and .motivo=="LIQUIDACAO_NEGADA"' >/dev/null
await_sql orchestrator "SELECT string_agg(response_type,',' ORDER BY completed_at) FROM saga_steps WHERE saga_id='$saga'" \
    'SaldoReservado,AtivoReservado,LiquidacaoFalhou,ReservaAtivoDesfeita,ReservaSaldoDesfeita,OrdemRejeitada'
await_sql settlements "SELECT count(*) FROM settlements WHERE order_id='$order'" 0
await_sql accounts "SELECT status FROM balance_reservations WHERE order_id='$order'" RELEASED
await_sql custody "SELECT status FROM asset_reservations WHERE order_id='$order'" RELEASED
await_sql accounts "SELECT available_balance,reserved_balance,total_consumed FROM accounts WHERE account_id='CLI001'" '1000.00|0.00|0.00'
await_sql custody "SELECT available_quantity,reserved_quantity FROM positions WHERE owner_id='COUNTERPARTY' AND asset_id='ATV002'" '10|0'
check_observability REJEITADA
echo "Recusa durável e compensação inversa verificadas."

stop settlement; unset SETTLEMENT_SIMULATION_DENY_ORDER_ID
buy reinicio-apos-liquidacao
stop custody
start settlement; ready settlement
await_sql orchestrator "SELECT response_type FROM saga_steps WHERE saga_id='$saga' AND command_type='ConfirmarReservaSaldo'" SaldoConfirmado
stop orchestrator KILL
start custody; ready custody
asset_op=$(sql orchestrator "SELECT operation_id FROM saga_steps WHERE saga_id='$saga' AND command_type='ConfirmarReservaAtivo'")
await_sql custody "SELECT published_at IS NOT NULL FROM outbox_messages WHERE operation_id='$asset_op'" t
# Consume the one confirmed reply with the coordinator offline, exclusively in this run's broker.
vhost=$(jq -rn --arg name "$RABBITMQ_VHOST" '$name|@uri')
curl -fsS -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" -H 'Content-Type: application/json' \
    -d '{"count":1,"ackmode":"ack_requeue_false","encoding":"auto","truncate":65536}' \
    "http://127.0.0.1:$RABBITMQ_MANAGEMENT_PORT/api/queues/$vhost/saga.v1.orchestrator/get" \
    | jq -e --arg op "$asset_op" '.[0].payload | fromjson | .type=="AtivoConfirmado" and .operationId==$op' >/dev/null

export RECONCILIATION_POLL_INTERVAL=1s SAGA_TIMEOUT_STEP=2s
start orchestrator; ready orchestrator
await_sql orchestrator "SELECT state FROM saga_instances WHERE saga_id='$saga'" CONCLUIDA
curl -fsS "$api/$order" | jq -e '.estado=="LIQUIDADA" and .liquidacaoId!=null' >/dev/null
await_sql orchestrator "SELECT retry_count>0 FROM saga_steps WHERE operation_id='$asset_op'" t
await_sql orchestrator "SELECT count(*) FROM saga_steps WHERE saga_id='$saga' AND command_type LIKE 'Desfazer%'" 0
await_sql settlements "SELECT count(*) FROM settlements WHERE saga_id='$saga'" 1
await_sql accounts "SELECT available_balance,reserved_balance,total_consumed FROM accounts WHERE account_id='CLI001'" '974.50|0.00|25.50'
await_sql custody "SELECT available_quantity,reserved_quantity FROM positions WHERE owner_id='CLI001' AND asset_id='ATV002'" '1|0'
check_observability CONCLUIDA
echo "Reinício após liquidação e recuperação da resposta perdida verificados."
