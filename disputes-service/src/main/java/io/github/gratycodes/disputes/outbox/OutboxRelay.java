package io.github.gratycodes.disputes.outbox;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the outbox and publishes in id order. Rows are claimed with FOR UPDATE SKIP LOCKED, so
 * several service instances can run the relay. Delivery is at least once: a crash after the send
 * and before the commit republishes the row, so consumers dedupe on event_id. A failed send stops
 * the batch to keep per-dispute ordering.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(long id, String topic, String key, String payload) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Optional<EventPublisher> publisher;
    private final int batchSize;

    public OutboxRelay(JdbcClient jdbc, TransactionTemplate tx, Optional<EventPublisher> publisher,
                       @Value("${copilot.outbox.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${copilot.outbox.poll-interval:1s}", initialDelayString = "${copilot.outbox.initial-delay:2s}")
    public void scheduled() {
        try {
            relayOnce();
        } catch (RuntimeException e) {
            log.warn("outbox relay pass failed: {}", e.toString());
        }
    }

    /** Publishes up to one batch. Returns how many rows were published. */
    public int relayOnce() {
        if (publisher.isEmpty()) {
            return 0;
        }
        Integer n = tx.execute(status -> {
            List<Row> rows = jdbc.sql("""
                            SELECT id, topic, message_key, payload::text AS payload FROM outbox
                            WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""")
                    .param(batchSize)
                    .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("topic"),
                            rs.getString("message_key"), rs.getString("payload")))
                    .list();
            int done = 0;
            for (Row r : rows) {
                try {
                    publisher.get().publish(r.topic(), r.key(), r.payload());
                } catch (Exception e) {
                    jdbc.sql("UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?")
                            .params(String.valueOf(e.getMessage()), r.id()).update();
                    log.warn("outbox publish failed for id {}: {}", r.id(), e.toString());
                    break;
                }
                jdbc.sql("UPDATE outbox SET published_at = now(), attempts = attempts + 1 WHERE id = ?")
                        .param(r.id()).update();
                done++;
            }
            return done;
        });
        return n == null ? 0 : n;
    }

    public int pending() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Integer.class).single();
    }
}
