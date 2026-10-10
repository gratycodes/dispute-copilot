package io.github.gratycodes.disputes.persistence;

import io.github.gratycodes.disputes.domain.Decision;
import io.github.gratycodes.disputes.domain.DisputeStatus;
import io.github.gratycodes.disputes.service.NotFoundException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes and review-side reads for disputes, recommendations and decisions. */
@Repository
public class DisputeStore {

    /** Row-locked view of a dispute used inside write transactions. */
    public record Locked(String id, String transactionId, String customerId, String merchantId,
                         long amountMinor, String currency, DisputeStatus status, int version) {}

    public record Rec(String id, Decision decision) {}

    private final JdbcClient jdbc;
    private final Json json;

    public DisputeStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Locked lock(String disputeId) {
        return jdbc.sql("""
                        SELECT d.id, d.transaction_id, t.customer_id, t.merchant_id, d.amount_minor, d.currency,
                               d.status, d.version
                        FROM disputes d JOIN transactions t ON t.id = d.transaction_id
                        WHERE d.id = ? FOR UPDATE OF d""")
                .param(disputeId)
                .query((rs, n) -> new Locked(rs.getString("id"), rs.getString("transaction_id"),
                        rs.getString("customer_id"), rs.getString("merchant_id"), rs.getLong("amount_minor"),
                        rs.getString("currency"), DisputeStatus.valueOf(rs.getString("status")), rs.getInt("version")))
                .optional()
                .orElseThrow(() -> new NotFoundException("dispute " + disputeId));
    }

    public Optional<Map<String, Object>> transactionForNewDispute(String transactionId) {
        return jdbc.sql("SELECT amount_minor, currency, merchant_id FROM transactions WHERE id = ?")
                .param(transactionId)
                .query((rs, n) -> Map.<String, Object>of("amount_minor", rs.getLong("amount_minor"),
                        "currency", rs.getString("currency"), "merchant_id", rs.getString("merchant_id")))
                .optional();
    }

    public void insertDispute(String id, String transactionId, String reasonCode, long amount, String currency,
                              String message, OffsetDateTime openedAt, DisputeStatus status) {
        jdbc.sql("""
                        INSERT INTO disputes (id, transaction_id, reason_code, amount_minor, currency,
                                              cardholder_message, opened_at, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, transactionId, reasonCode, amount, currency, message, openedAt, status.name())
                .update();
    }

    public void setStatus(Locked d, DisputeStatus to) {
        int n = jdbc.sql("""
                        UPDATE disputes SET status = ?, version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ?""")
                .params(to.name(), d.id(), d.version())
                .update();
        if (n != 1) {
            throw new IllegalStateException("dispute " + d.id() + " changed while locked");
        }
    }

    public void appendMessage(String disputeId, String text) {
        jdbc.sql("UPDATE disputes SET cardholder_message = cardholder_message || ? WHERE id = ?")
                .params(text, disputeId).update();
    }

    public void insertRecommendation(String id, String disputeId, Map<String, Object> r) {
        jdbc.sql("""
                        INSERT INTO recommendations (id, dispute_id, agent_version, model, decision, confidence,
                            policy_rule, rationale, citations, flags, usage, trace_id, trace)
                        VALUES (:id, :dispute, :version, :model, :decision, :confidence, :rule, :rationale,
                                :citations::jsonb, :flags::jsonb, :usage::jsonb, :traceId, :trace::jsonb)""")
                .param("id", id)
                .param("dispute", disputeId)
                .param("version", r.get("agent_version"))
                .param("model", r.get("model"))
                .param("decision", r.get("decision"))
                .param("confidence", r.get("confidence"))
                .param("rule", r.get("policy_rule"))
                .param("rationale", r.get("rationale"))
                .param("citations", json.write(r.get("citations")))
                .param("flags", json.write(r.get("flags")))
                .param("usage", r.get("usage") == null ? null : json.write(r.get("usage")))
                .param("traceId", r.get("trace_id"))
                .param("trace", r.get("trace") == null ? null : json.write(r.get("trace")))
                .update();
    }

    public Optional<Rec> latestRecommendation(String disputeId) {
        return jdbc.sql("""
                        SELECT id, decision FROM recommendations WHERE dispute_id = ?
                        ORDER BY created_at DESC, id DESC LIMIT 1""")
                .param(disputeId)
                .query((rs, n) -> new Rec(rs.getString("id"), Decision.valueOf(rs.getString("decision"))))
                .optional();
    }

    public void insertDecision(String id, String disputeId, String recommendationId, Decision agent, Decision fin,
                               boolean agreed, String reason, String reviewer) {
        jdbc.sql("""
                        INSERT INTO decisions (id, dispute_id, recommendation_id, agent_decision, final_decision,
                                               agreed, reason, reviewer)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, disputeId, recommendationId, agent.name(), fin.name(), agreed, reason, reviewer)
                .update();
    }

    // ---- review-side reads -------------------------------------------------------------

    public List<Map<String, Object>> list(String status, int limit) {
        return jdbc.sql("""
                        SELECT d.id, d.reason_code, d.amount_minor, d.currency, d.status, d.opened_at, d.version,
                               t.customer_id, m.name AS merchant_name,
                               r.id AS rec_id, r.decision AS rec_decision, r.confidence AS rec_confidence,
                               r.flags::text AS rec_flags, r.agent_version AS rec_agent
                        FROM disputes d
                        JOIN transactions t ON t.id = d.transaction_id
                        JOIN merchants m ON m.id = t.merchant_id
                        LEFT JOIN LATERAL (
                            SELECT * FROM recommendations x WHERE x.dispute_id = d.id
                            ORDER BY x.created_at DESC, x.id DESC LIMIT 1) r ON true
                        WHERE (CAST(:status AS text) IS NULL OR d.status = :status)
                        ORDER BY d.opened_at, d.id
                        LIMIT :limit""")
                .param("status", status)
                .param("limit", limit)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("reason_code", rs.getString("reason_code"));
                    m.put("amount_minor", rs.getLong("amount_minor"));
                    m.put("currency", rs.getString("currency"));
                    m.put("status", rs.getString("status"));
                    m.put("opened_at", Times.iso(rs, "opened_at"));
                    m.put("version", rs.getInt("version"));
                    m.put("customer_id", rs.getString("customer_id"));
                    m.put("merchant_name", rs.getString("merchant_name"));
                    if (rs.getString("rec_id") != null) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("id", rs.getString("rec_id"));
                        r.put("decision", rs.getString("rec_decision"));
                        r.put("confidence", rs.getBigDecimal("rec_confidence"));
                        r.put("flags", json.read(rs.getString("rec_flags")));
                        r.put("agent_version", rs.getString("rec_agent"));
                        m.put("latest_recommendation", r);
                    } else {
                        m.put("latest_recommendation", null);
                    }
                    return m;
                })
                .list();
    }

    public Map<String, Integer> countsByStatus() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (DisputeStatus s : DisputeStatus.values()) {
            out.put(s.name(), 0);
        }
        jdbc.sql("SELECT status, count(*) AS n FROM disputes GROUP BY status")
                .query((rs, n) -> out.put(rs.getString("status"), rs.getInt("n")))
                .list();
        return out;
    }

    public List<Map<String, Object>> recommendations(String disputeId) {
        return jdbc.sql("""
                        SELECT id, agent_version, model, decision, confidence, policy_rule, rationale,
                               citations::text AS citations, flags::text AS flags, usage::text AS usage,
                               trace_id, trace IS NOT NULL AS has_trace, created_at
                        FROM recommendations WHERE dispute_id = ? ORDER BY created_at DESC, id DESC""")
                .param(disputeId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("agent_version", rs.getString("agent_version"));
                    m.put("model", rs.getString("model"));
                    m.put("decision", rs.getString("decision"));
                    m.put("confidence", rs.getBigDecimal("confidence"));
                    m.put("policy_rule", rs.getString("policy_rule"));
                    m.put("rationale", rs.getString("rationale"));
                    m.put("citations", json.read(rs.getString("citations")));
                    m.put("flags", json.read(rs.getString("flags")));
                    m.put("usage", json.read(rs.getString("usage")));
                    m.put("trace_id", rs.getString("trace_id"));
                    m.put("has_trace", rs.getBoolean("has_trace"));
                    m.put("created_at", Times.iso(rs, "created_at"));
                    return m;
                })
                .list();
    }

    public Optional<String> trace(String recommendationId) {
        return jdbc.sql("SELECT trace::text FROM recommendations WHERE id = ? AND trace IS NOT NULL")
                .param(recommendationId)
                .query(String.class)
                .optional();
    }

    public List<Map<String, Object>> decisions(String disputeIdOrNull, boolean overridesOnly) {
        return jdbc.sql("""
                        SELECT x.*, d.reason_code, r.agent_version, r.policy_rule
                        FROM decisions x
                        JOIN disputes d ON d.id = x.dispute_id
                        JOIN recommendations r ON r.id = x.recommendation_id
                        WHERE (CAST(:dispute AS text) IS NULL OR x.dispute_id = :dispute)
                          AND (NOT :overrides OR NOT x.agreed)
                        ORDER BY x.created_at DESC, x.id DESC""")
                .param("dispute", disputeIdOrNull)
                .param("overrides", overridesOnly)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("dispute_id", rs.getString("dispute_id"));
                    m.put("reason_code", rs.getString("reason_code"));
                    m.put("recommendation_id", rs.getString("recommendation_id"));
                    m.put("agent_version", rs.getString("agent_version"));
                    m.put("agent_policy_rule", rs.getString("policy_rule"));
                    m.put("agent_decision", rs.getString("agent_decision"));
                    m.put("final_decision", rs.getString("final_decision"));
                    m.put("agreed", rs.getBoolean("agreed"));
                    m.put("reason", rs.getString("reason"));
                    m.put("reviewer", rs.getString("reviewer"));
                    m.put("decided_at", Times.iso(rs, "created_at"));
                    return m;
                })
                .list();
    }
}
