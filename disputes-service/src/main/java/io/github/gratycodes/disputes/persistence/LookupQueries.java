package io.github.gratycodes.disputes.persistence;

import io.github.gratycodes.disputes.service.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read models the triage agent's MCP tools use. Field names and ordering match
 * mcp-server's FixtureBackend exactly; the integration job in CI compares the two.
 */
@Repository
public class LookupQueries {

    private static final String TXN_COLUMNS = """
            t.id, t.customer_id, t.merchant_id, t.amount_minor, t.currency, t.created_at, t.three_ds,
            t.device_id, t.billing_address, t.shipping_address, t.order_ref, t.recurring""";

    private final JdbcClient jdbc;
    private final Json json;

    public LookupQueries(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Map<String, Object> dispute(String id) {
        return jdbc.sql("""
                        SELECT d.*, t.customer_id, m.id AS merchant_id, m.name AS merchant_name, m.mcc
                        FROM disputes d
                        JOIN transactions t ON t.id = d.transaction_id
                        JOIN merchants m ON m.id = t.merchant_id
                        WHERE d.id = ?""")
                .param(id)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("transaction_id", rs.getString("transaction_id"));
                    m.put("customer_id", rs.getString("customer_id"));
                    m.put("merchant", Map.of("id", rs.getString("merchant_id"),
                            "name", rs.getString("merchant_name"), "mcc", rs.getString("mcc")));
                    m.put("reason_code", rs.getString("reason_code"));
                    m.put("amount_minor", rs.getLong("amount_minor"));
                    m.put("currency", rs.getString("currency"));
                    m.put("cardholder_message", rs.getString("cardholder_message"));
                    m.put("opened_at", Times.iso(rs, "opened_at"));
                    m.put("status", rs.getString("status"));
                    m.put("version", rs.getInt("version"));
                    m.put("updated_at", Times.iso(rs, "updated_at"));
                    return m;
                })
                .optional()
                .orElseThrow(() -> new NotFoundException("dispute " + id));
    }

    public Map<String, Object> transaction(String id) {
        return jdbc.sql("SELECT " + TXN_COLUMNS + " FROM transactions t WHERE t.id = ?")
                .param(id)
                .query((rs, n) -> txn(rs))
                .optional()
                .orElseThrow(() -> new NotFoundException("transaction " + id));
    }

    public List<Map<String, Object>> evidence(String transactionId) {
        transaction(transactionId); // 404 for unknown transactions, like the fixture backend
        return jdbc.sql("""
                        SELECT id, transaction_id, kind, payload::text AS payload, merchant_note, submitted_at
                        FROM merchant_evidence WHERE transaction_id = ? ORDER BY id""")
                .param(transactionId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("transaction_id", rs.getString("transaction_id"));
                    m.put("kind", rs.getString("kind"));
                    m.put("payload", json.read(rs.getString("payload")));
                    m.put("merchant_note", rs.getString("merchant_note"));
                    m.put("submitted_at", Times.iso(rs, "submitted_at"));
                    return m;
                })
                .list();
    }

    public Map<String, Object> customerHistory(String disputeId) {
        Map<String, Object> d = dispute(disputeId);
        String customerId = (String) d.get("customer_id");
        String disputedTxn = (String) d.get("transaction_id");
        Map<String, Object> customer = jdbc.sql("SELECT id, name, country, created_at FROM customers WHERE id = ?")
                .param(customerId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("name", rs.getString("name"));
                    m.put("country", rs.getString("country"));
                    m.put("created_at", Times.iso(rs, "created_at"));
                    return m;
                })
                .single();
        List<Map<String, Object>> txns = jdbc.sql("SELECT " + TXN_COLUMNS + """
                        , EXISTS (SELECT 1 FROM disputes x WHERE x.transaction_id = t.id) AS disputed
                        FROM transactions t
                        WHERE t.customer_id = ? AND t.id <> ?
                        ORDER BY t.created_at DESC, t.id DESC""")
                .params(customerId, disputedTxn)
                .query((rs, n) -> {
                    Map<String, Object> m = txn(rs);
                    m.put("disputed", rs.getBoolean("disputed"));
                    return m;
                })
                .list();
        List<Map<String, Object>> others = jdbc.sql("""
                        SELECT x.id, x.transaction_id, x.reason_code, x.opened_at, x.status
                        FROM disputes x JOIN transactions t ON t.id = x.transaction_id
                        WHERE t.customer_id = ? AND t.id <> ?
                        ORDER BY x.opened_at DESC, x.id DESC""")
                .params(customerId, disputedTxn)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("transaction_id", rs.getString("transaction_id"));
                    m.put("reason_code", rs.getString("reason_code"));
                    m.put("opened_at", Times.iso(rs, "opened_at"));
                    m.put("status", rs.getString("status"));
                    return m;
                })
                .list();
        Integer recent = jdbc.sql("""
                        SELECT count(*) FROM disputes x JOIN transactions t ON t.id = x.transaction_id
                        WHERE t.customer_id = ? AND t.id <> ?
                          AND x.opened_at >= (SELECT opened_at FROM disputes WHERE id = ?) - interval '180 days'""")
                .params(customerId, disputedTxn, disputeId)
                .query(Integer.class)
                .single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customer", customer);
        out.put("transactions", txns);
        out.put("other_disputes", others);
        out.put("other_disputes_last_180d", recent);
        return out;
    }

    private static Map<String, Object> txn(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("customer_id", rs.getString("customer_id"));
        m.put("merchant_id", rs.getString("merchant_id"));
        m.put("amount_minor", rs.getLong("amount_minor"));
        m.put("currency", rs.getString("currency"));
        m.put("created_at", Times.iso(rs, "created_at"));
        m.put("three_ds", rs.getString("three_ds"));
        m.put("device_id", rs.getString("device_id"));
        m.put("billing_address", rs.getString("billing_address"));
        m.put("shipping_address", rs.getString("shipping_address"));
        m.put("order_ref", rs.getString("order_ref"));
        m.put("recurring", rs.getBoolean("recurring"));
        return m;
    }
}
