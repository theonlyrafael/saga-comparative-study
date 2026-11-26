ALTER TABLE saga_instances ADD COLUMN order_received_at TIMESTAMPTZ;
ALTER TABLE saga_steps ADD COLUMN first_timeout_at TIMESTAMPTZ;

CREATE VIEW saga_measurements AS
SELECT saga.saga_id, saga.order_id, saga.state,
       extract(epoch FROM (saga.updated_at - saga.order_received_at)) AS latency_seconds,
       extract(epoch FROM (timing.release_end - timing.release_start)) AS compensation_seconds,
       extract(epoch FROM (saga.updated_at - least(timing.refused_at, timing.timeout_at))) AS convergence_seconds
FROM saga_instances saga
CROSS JOIN LATERAL (
    SELECT min(completed_at) FILTER (WHERE response_type IN ('SaldoRecusado','AtivoRecusado','LiquidacaoFalhou')) AS refused_at,
           min(first_timeout_at) AS timeout_at,
           min(issued_at) FILTER (WHERE command_type IN ('DesfazerReservaSaldo','DesfazerReservaAtivo')) AS release_start,
           max(completed_at) FILTER (WHERE response_type IN ('ReservaSaldoDesfeita','ReservaAtivoDesfeita')) AS release_end
    FROM saga_steps WHERE saga_id=saga.saga_id
) timing
WHERE saga.state IN ('CONCLUIDA','REJEITADA');
