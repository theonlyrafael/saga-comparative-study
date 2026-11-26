#!/usr/bin/env bash
# Exercise both sides of the settlement decision with private volumes, broker and JVMs.
set -euo pipefail
cd "$(dirname "$0")/../.."
source infra/scripts/lib/saga-test-stack.sh
init_stack recovery

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
