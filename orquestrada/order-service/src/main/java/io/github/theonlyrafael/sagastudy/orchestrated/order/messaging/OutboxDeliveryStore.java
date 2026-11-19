package io.github.theonlyrafael.sagastudy.orchestrated.order.messaging;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class OutboxDeliveryStore {
    private final JdbcTemplate jdbc;

    public OutboxDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PendingMessage> lockNext() {
        return jdbc.query("""
                SELECT message_id, message_type, envelope::text FROM outbox_messages
                WHERE published_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY next_attempt_at, created_at, message_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (row, index) -> new PendingMessage(row.getObject(1, UUID.class),
                        row.getString(2), row.getString(3))).stream().findFirst();
    }

    public void markPublished(UUID id) {
        jdbc.update("UPDATE outbox_messages SET published_at = clock_timestamp() WHERE message_id = ?", id);
    }

    public void defer(UUID id, long delayMillis) {
        jdbc.update("""
                UPDATE outbox_messages SET next_attempt_at = clock_timestamp() + ? * interval '1 millisecond'
                WHERE message_id = ?
                """, delayMillis, id);
    }
}
