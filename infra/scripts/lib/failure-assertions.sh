# All HTTP calls and database reads address the private stack of this run.
purchase() {
    local key=$1 buyer=${2:-CLI001} asset=${3:-ATV002}
    curl -fsS --max-time 10 -H "Idempotency-Key: $key" -H 'Content-Type: application/json' \
        -d "{\"clienteId\":\"$buyer\",\"ativoId\":\"$asset\",\"quantidade\":1}" "$api" |
        jq -ce --arg buyer "$buyer" --arg asset "$asset" '
            select(.estado == "RECEBIDA" and .clienteId == $buyer and .ativoId == $asset and .quantidade == 1)'
}

use_receipt() {
    order=$(jq -er '.orderId' <<<"$1")
    saga=$(jq -er '.sagaId' <<<"$1")
}

await_step() {
    await_sql orchestrator "SELECT response_type FROM saga_steps
        WHERE saga_id='$saga' AND command_type='$1'" "$2"
}

assert_sql() {
    local actual
    actual=$(sql "$1" "$2")
    if [[ $actual != "$3" ]]; then
        echo "$1: expected '$3', got '$actual' for $2" >&2
        return 1
    fi
}

# The CHECKs below compare data read through five independent service interfaces.
verify_order() {
    local outcome=$1 state=REJEITADA result cash asset settlement responses
    [[ $outcome != LIQUIDADA ]] || state=CONCLUIDA
    await_sql orchestrator "SELECT state FROM saga_instances WHERE saga_id='$saga' AND order_id='$order'" "$state"
    result=$(curl -fsS --max-time 5 "$api/$order")
    local pair="WHERE order_id='$order' AND saga_id='$saga'"
    cash=$(sql accounts "SELECT row_to_json(r) FROM (SELECT reservation_id AS id, status,
        account_id AS buyer, amount FROM balance_reservations $pair) r")
    asset=$(sql custody "SELECT row_to_json(r) FROM (SELECT reservation_id AS id, status,
        buyer_id AS buyer, asset_id AS asset, quantity FROM asset_reservations $pair) r")
    settlement=$(sql settlements "SELECT row_to_json(r) FROM (SELECT settlement_id AS id, status,
        balance_reservation_id AS cash, asset_reservation_id AS custody, buyer_id AS buyer, asset_id AS asset,
        amount, quantity, catalog_version AS catalog FROM settlements $pair) r")
    responses=$(sql orchestrator "SELECT json_agg(response_type ORDER BY completed_at, command_type)
        FROM saga_steps WHERE saga_id='$saga'")
    jq -nce --argjson result "$result" --arg order "$order" --arg saga "$saga" --arg state "$state" \
        --arg expected "$outcome" --argjson cash "${cash:-null}" --argjson asset "${asset:-null}" \
        --argjson settlement "${settlement:-null}" --argjson steps "$responses" \
        -f infra/scripts/lib/verify-saga-order.jq >> "$run_dir/orders.ndjson"
}

await_delivery() {
    local db queues deadline
    for db in orders accounts custody settlements orchestrator; do
        await_sql "$db" 'SELECT count(*) FROM outbox_messages WHERE published_at IS NULL' 0
    done
    deadline=$((SECONDS + 90))
    while (( SECONDS < deadline )); do
        queues=$("${compose[@]}" exec -T rabbitmq rabbitmqctl -q list_queues -p "$RABBITMQ_VHOST" \
            name messages_ready messages_unacknowledged --formatter json)
        if jq -e 'length > 0 and all(.[]; .messages_ready == 0 and .messages_unacknowledged == 0)' \
            <<<"$queues" > /dev/null; then return; fi
        sleep 1
    done
    echo "Pending deliveries after convergence: $queues" >&2
    return 1
}

# Start each invocation from demo-v1. Only the settled units specified by the cases may move.
check_ledger() {
    local debit=$((atv001 * 1000 + atv002 * 2550)) cash positions count
    cash=$(sql accounts "SELECT json_object_agg(account_id, json_build_array(
        (available_balance*100)::bigint,(reserved_balance*100)::bigint,(total_consumed*100)::bigint))
        FROM accounts")
    positions=$(sql custody "SELECT json_object_agg(asset_id, json_build_array(seller,buyer,reserved))
        FROM (SELECT asset_id, sum(available_quantity) FILTER (WHERE owner_id='COUNTERPARTY') AS seller,
        coalesce(sum(available_quantity) FILTER (WHERE owner_id='CLI001'),0) AS buyer,
        sum(reserved_quantity) AS reserved FROM positions GROUP BY asset_id) p")
    count=$(sql settlements 'SELECT count(*) FROM settlements')
    assert_sql accounts "SELECT count(*) FROM balance_reservations WHERE status='RESERVED'" 0
    assert_sql custody "SELECT count(*) FROM asset_reservations WHERE status='RESERVED'" 0
    jq -nce --argjson cash "$cash" --argjson positions "$positions" --argjson settlements "$count" \
        --argjson debit "$debit" --argjson a "$atv001" --argjson b "$atv002" '
        {cashCents: $cash, positions: $positions, settlements: $settlements} |
        if $cash == {CLI001:[(100000-$debit),0,$debit], CLI002:[1000,0,0]}
           and $positions == {ATV001:[(1-$a),$a,0], ATV002:[(10-$b),$b,0]}
           and $settlements == ($a+$b) then . else error("ledger: "+tostring) end'
}
