package io.github.gratycodes.disputes.persistence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Append-only per-dispute event log plus the transactional outbox. Callers must hold the
 * dispute's row lock (SELECT ... FOR UPDATE) so sequence numbers stay contiguous, and must call
 * this inside the same transaction as the state change.
 */
@Repository
public class EventLog {

    private final JdbcClient jdbc;
    private final Json json;
    private final String topic;

    public EventLog(JdbcClient jdbc, Json json, @Value("${copilot.outbox.topic:dispute-events}") String topic) {
        this.jdbc = jdbc;
        this.json = json;
        this.topic = topic;
    }

    public int append(String disputeId, String type, Map<String, Object> payload) {
        int seq = jdbc.sql("SELECT coalesce(max(seq), 0) + 1 FROM dispute_events WHERE dispute_id = ?")
                .param(disputeId).query(Integer.class).single();
        String body = json.write(payload);
        jdbc.sql("INSERT INTO dispute_events (dispute_id, seq, type, payload) VALUES (?, ?, ?, ?::jsonb)")
                .params(disputeId, seq, type, body).update();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", disputeId + "/" + seq);
        envelope.put("dispute_id", disputeId);
        envelope.put("seq", seq);
        envelope.put("type", type);
        envelope.put("occurred_at", Times.iso(Instant.now()));
        envelope.put("payload", payload);
        jdbc.sql("INSERT INTO outbox (topic, message_key, payload) VALUES (?, ?, ?::jsonb)")
                .params(topic, disputeId, json.write(envelope)).update();
        return seq;
    }

    public List<Map<String, Object>> events(String disputeId) {
        return jdbc.sql("""
                        SELECT seq, type, payload::text AS payload, created_at FROM dispute_events
                        WHERE dispute_id = ? ORDER BY seq""")
                .param(disputeId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("seq", rs.getInt("seq"));
                    m.put("type", rs.getString("type"));
                    m.put("payload", json.read(rs.getString("payload")));
                    m.put("created_at", Times.iso(rs, "created_at"));
                    return m;
                })
                .list();
    }
}
