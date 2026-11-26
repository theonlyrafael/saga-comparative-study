# Each case writes the injection it observed and verifies every order it created.
C1() {
    local since
    # Hold the settlement command until the custody JVM has crashed.
    stop settlement
    use_receipt "$(purchase C1)"
    await_step ReservarAtivo AtivoReservado
    since=$SECONDS
    stop custody KILL
    start settlement
    ready settlement
    await_step ConfirmarReservaSaldo SaldoConfirmado
    assert_sql settlements "SELECT status FROM settlements WHERE order_id='$order'" CONFIRMED
    assert_sql custody "SELECT status FROM asset_reservations WHERE order_id='$order'" RESERVED
    assert_sql orders "SELECT status FROM orders WHERE order_id='$order'" RECEBIDA
    witness --argjson seconds "$((SECONDS-since))" \
        '{participant:"custody",signal:"SIGKILL",secondsUntilRestart:$seconds,
          beforeRestart:{settlement:"CONFIRMED",cashReply:"SaldoConfirmado",assetReservation:"RESERVED",order:"RECEBIDA"}}'
    start custody
    ready custody
    verify_order LIQUIDADA
    atv002=$((atv002+1))
}

C2() {
    local locker lock_fd since retries
    # A separate connection holds only the buyer row. Keep the participant and its consumer running.
    mkfifo "$run_dir/row-lock.sql"
    "${compose[@]}" exec -T postgres psql -XAtq -v ON_ERROR_STOP=1 -U accounts_user -d accounts_db \
        < "$run_dir/row-lock.sql" > "$run_dir/row-lock.log" 2>&1 &
    locker=$!
    exec {lock_fd}>"$run_dir/row-lock.sql"
    printf '%s\n' "BEGIN; SET LOCAL application_name='matrix_c2'; SET LOCAL idle_in_transaction_session_timeout='90s';" \
        "SELECT account_id FROM accounts WHERE account_id='CLI001' FOR UPDATE;" >&"$lock_fd"
    await_sql accounts "SELECT count(*) FROM pg_stat_activity
        WHERE application_name='matrix_c2' AND state='idle in transaction'" 1
    since=$SECONDS
    use_receipt "$(purchase C2)"
    await_sql accounts "SELECT EXISTS (SELECT FROM pg_stat_activity waiter JOIN pg_stat_activity holder
        ON holder.pid=ANY(pg_blocking_pids(waiter.pid)) WHERE holder.application_name='matrix_c2')" t
    await_sql orchestrator "SELECT retry_count>0 AND first_timeout_at IS NOT NULL AND completed_at IS NULL
        FROM saga_steps WHERE saga_id='$saga' AND command_type='ReservarSaldo'" t
    assert_sql orders "SELECT status FROM orders WHERE order_id='$order'" RECEBIDA
    retries=$(sql orchestrator "SELECT retry_count FROM saga_steps WHERE saga_id='$saga' AND command_type='ReservarSaldo'")
    witness --argjson seconds "$((SECONDS-since))" --argjson retries "$retries" \
        '{blockedRow:"accounts/CLI001",secondsUntilUnlock:$seconds,retriesBeforeUnlock:$retries,orderWhileBlocked:"RECEBIDA"}'
    echo 'ROLLBACK;' >&"$lock_fd"
    exec {lock_fd}>&-
    wait "$locker"
    verify_order LIQUIDADA
    atv002=$((atv002+1))
}

C3() {
    local first_pid second_pid receipt status first second
    stop custody
    purchase C3-A CLI001 ATV001 > "$run_dir/C3-A.json" &
    first_pid=$!
    purchase C3-B CLI001 ATV001 > "$run_dir/C3-B.json" &
    second_pid=$!
    wait "$first_pid"
    wait "$second_pid"
    for receipt in "$run_dir/C3-"{A,B}.json; do
        use_receipt "$(<"$receipt")"
        await_step ReservarSaldo SaldoReservado
    done
    first=$(jq -er .orderId "$run_dir/C3-A.json")
    second=$(jq -er .orderId "$run_dir/C3-B.json")
    assert_sql accounts "SELECT count(*) FROM balance_reservations
        WHERE order_id IN ('$first','$second') AND status='RESERVED'" 2
    assert_sql custody "SELECT available_quantity FROM positions WHERE owner_id='COUNTERPARTY' AND asset_id='ATV001'" 1
    start custody
    ready custody
    await_sql orders "SELECT string_agg(status,',' ORDER BY status) FROM orders
        WHERE order_id IN ('$first','$second')" LIQUIDADA,REJEITADA
    for receipt in "$run_dir/C3-"{A,B}.json; do
        use_receipt "$(<"$receipt")"
        status=$(sql orders "SELECT status FROM orders WHERE order_id='$order'")
        if [[ $status == LIQUIDADA ]]; then verify_order LIQUIDADA; else verify_order ATIVO_INSUFICIENTE; fi
    done
    use_receipt "$(purchase C3-cash CLI002 ATV002)"
    verify_order SALDO_INSUFICIENTE
    witness '{competingOrders:2,reservedBalancesBeforeCustody:2,availableUnits:1,insufficientCashBuyer:"CLI002"}'
    atv001=$((atv001+1))
}

publish_command() {
    local body vhost
    body=$(jq -c '{properties:{content_type:"application/json",delivery_mode:2,message_id:.messageId,type:.type},
        routing_key:.type,payload:(tojson),payload_encoding:"string"}' <<<"$1")
    vhost=$(jq -rn --arg name "$RABBITMQ_VHOST" '$name|@uri')
    curl -fsS --max-time 10 -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" -H 'Content-Type: application/json' \
        -d "$body" "http://127.0.0.1:$RABBITMQ_MANAGEMENT_PORT/api/exchanges/$vhost/saga.v1.commands/publish" |
        jq -e '.routed == true' > /dev/null
}

C4() {
    local op envelope before new_id since publication
    use_receipt "$(purchase C4)"
    await_step FinalizarOrdem OrdemLiquidada
    op=$(sql orchestrator "SELECT operation_id FROM saga_steps
        WHERE saga_id='$saga' AND command_type='ConfirmarReservaAtivo'")
    envelope=$(sql orchestrator "SELECT envelope FROM outbox_messages WHERE operation_id='$op'")
    publication="SELECT published_at FROM outbox_messages WHERE operation_id='$op'"
    before=$(sql custody "$publication")
    since=$SECONDS
    publish_command "$envelope"
    await_sql custody "SELECT published_at>'$before'::timestamptz FROM outbox_messages WHERE operation_id='$op'" t

    before=$(sql custody "$publication")
    new_id=$(sql custody 'SELECT gen_random_uuid()')
    publish_command "$(jq -c --arg id "$new_id" '.messageId=$id' <<<"$envelope")"
    await_sql custody "SELECT count(*) FROM inbox_deliveries WHERE operation_id='$op' AND message_id='$new_id'" 1
    await_sql custody "SELECT published_at>'$before'::timestamptz FROM outbox_messages WHERE operation_id='$op'" t
    assert_sql custody "SELECT count(*) FROM inbox_deliveries WHERE operation_id='$op'" 2
    assert_sql custody "SELECT count(*) FROM inbox_messages WHERE operation_id='$op'" 1
    witness --arg op "$op" --arg replayId "$new_id" --argjson seconds "$((SECONDS-since))" \
        '{command:"ConfirmarReservaAtivo",operationId:$op,newMessageId:$replayId,
          secondsForTwoReplays:$seconds,distinctDeliveryIds:2,processedOperations:1}'
    verify_order LIQUIDADA
    atv002=$((atv002+1))
}

C5() {
    local receipt message since
    stop order
    OUTBOX_PUBLISHER_ENABLED=false start order
    ready order
    api="http://127.0.0.1:${ports[order]}/api/ordens"
    receipt=$(purchase C5)
    use_receipt "$receipt"
    since=$SECONDS
    message=$(sql orders "SELECT message_id FROM outbox_messages
        WHERE order_id='$order' AND message_type='OrdemRecebida' AND published_at IS NULL")
    [[ -n $message ]]
    stop order KILL
    assert_sql orders "SELECT status FROM orders WHERE order_id='$order'" RECEBIDA
    assert_sql orders "SELECT published_at IS NULL FROM outbox_messages WHERE message_id='$message'" t
    assert_sql orchestrator "SELECT count(*) FROM saga_instances WHERE saga_id='$saga'" 0
    start order
    ready order
    api="http://127.0.0.1:${ports[order]}/api/ordens"
    await_sql orders "SELECT published_at IS NOT NULL FROM outbox_messages WHERE message_id='$message'" t
    witness --arg id "$message" --argjson seconds "$((SECONDS-since))" \
        '{event:"OrdemRecebida",messageId:$id,unpublishedAfterCrash:true,sagasBeforeRestart:0,
          secondsFromReceiptToObservedPublication:$seconds}'
    verify_order LIQUIDADA
    local replay
    replay=$(purchase C5)
    jq -ne --argjson first "$receipt" --argjson replay "$replay" '$first == $replay' > /dev/null
    atv002=$((atv002+1))
}

C6() {
    local since cash_id asset_id
    stop settlement
    use_receipt "$(purchase C6)"
    await_step ReservarAtivo AtivoReservado
    cash_id=$(sql accounts "SELECT reservation_id FROM balance_reservations WHERE order_id='$order' AND status='RESERVED'")
    asset_id=$(sql custody "SELECT reservation_id FROM asset_reservations WHERE order_id='$order' AND status='RESERVED'")
    [[ -n $cash_id && -n $asset_id ]]
    stop account
    since=$SECONDS
    SETTLEMENT_SIMULATION_DENY_ORDER_ID="$order" start settlement
    ready settlement
    await_step DesfazerReservaAtivo ReservaAtivoDesfeita
    assert_sql settlements "SELECT outcome FROM settlement_decisions WHERE order_id='$order' AND saga_id='$saga'
        AND balance_reservation_id='$cash_id' AND asset_reservation_id='$asset_id'" DENIED
    assert_sql settlements "SELECT count(*) FROM settlements WHERE order_id='$order'" 0
    await_sql orchestrator "SELECT first_timeout_at IS NOT NULL AND completed_at IS NULL FROM saga_steps
        WHERE saga_id='$saga' AND command_type='DesfazerReservaSaldo'" t
    assert_sql custody "SELECT status FROM asset_reservations WHERE reservation_id='$asset_id'" RELEASED
    assert_sql accounts "SELECT status FROM balance_reservations WHERE reservation_id='$cash_id'" RESERVED
    assert_sql orders "SELECT status FROM orders WHERE order_id='$order'" RECEBIDA
    witness --argjson seconds "$((SECONDS-since))" \
        '{settlementDecision:"DENIED",secondsUntilAccountRestart:$seconds,
          beforeRestart:{settlements:0,assetReservation:"RELEASED",cashReservation:"RESERVED",order:"RECEBIDA"}}'
    start account
    ready account
    verify_order LIQUIDACAO_NEGADA
}
