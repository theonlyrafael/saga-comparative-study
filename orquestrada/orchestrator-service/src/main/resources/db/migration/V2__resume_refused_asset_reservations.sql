-- V1 persisted the custody refusal but did not issue the balance release.
-- The pending Saga, its new step and the publishable command advance together.
WITH refused AS (
    SELECT saga.saga_id, command.envelope, gen_random_uuid() AS operation_id, gen_random_uuid() AS message_id
    FROM saga_instances saga
    JOIN saga_steps asset ON asset.saga_id = saga.saga_id AND asset.command_type = 'ReservarAtivo'
    JOIN inbox_operations refusal ON refusal.operation_id = asset.operation_id
    JOIN outbox_messages command ON command.operation_id = asset.operation_id
    WHERE saga.state = 'RECUPERACAO_PENDENTE' AND asset.response_type = 'AtivoRecusado'
      AND refusal.content->>'type' = 'AtivoRecusado'
      AND NOT EXISTS (SELECT 1 FROM saga_steps settlement
                      WHERE settlement.saga_id = saga.saga_id AND settlement.command_type = 'ConfirmarLiquidacao')
), releases AS (
    INSERT INTO saga_steps (operation_id, saga_id, command_type)
    SELECT operation_id, saga_id, 'DesfazerReservaSaldo' FROM refused
    RETURNING operation_id, saga_id, command_type
), commands AS (
    INSERT INTO outbox_messages (message_id, operation_id, message_type, envelope)
    SELECT r.message_id, s.operation_id, s.command_type,
           r.envelope || jsonb_build_object('type', s.command_type, 'operationId', s.operation_id,
               'messageId', r.message_id, 'occurredAt', CURRENT_TIMESTAMP)
    FROM releases s JOIN refused r USING (operation_id)
    RETURNING operation_id
)
UPDATE saga_instances saga SET state = 'COMPENSANDO', updated_at = CURRENT_TIMESTAMP
FROM refused r JOIN commands c USING (operation_id)
WHERE saga.saga_id = r.saga_id;
