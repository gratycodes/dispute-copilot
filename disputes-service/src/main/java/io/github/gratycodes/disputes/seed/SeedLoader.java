package io.github.gratycodes.disputes.seed;

import io.github.gratycodes.disputes.domain.DisputeStatus;
import io.github.gratycodes.disputes.persistence.DisputeStore;
import io.github.gratycodes.disputes.persistence.Json;
import io.github.gratycodes.disputes.persistence.Times;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Loads data/seed.json (synthetic) into an empty database. Seeded disputes are imported as-is,
 * without event history; only disputes created through the API get events.
 */
@Component
public class SeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final JdbcClient jdbc;
    private final Json json;
    private final DisputeStore store;
    private final TransactionTemplate tx;
    private final String path;

    public SeedLoader(JdbcClient jdbc, Json json, DisputeStore store, TransactionTemplate tx,
                      @Value("${copilot.seed.path:}") String path) {
        this.jdbc = jdbc;
        this.json = json;
        this.store = store;
        this.tx = tx;
        this.path = path;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (path == null || path.isBlank()) {
            return;
        }
        int existing = jdbc.sql("SELECT count(*) FROM customers").query(Integer.class).single();
        if (existing > 0) {
            log.info("seed skipped: database already has data");
            return;
        }
        JsonNode seed = json.read(Files.readString(Path.of(path)));
        tx.executeWithoutResult(s -> load(seed));
        log.info("seeded {} disputes from {}", seed.get("disputes").size(), path);
    }

    void load(JsonNode seed) {
        for (JsonNode m : seed.get("merchants")) {
            jdbc.sql("INSERT INTO merchants (id, name, mcc) VALUES (?, ?, ?)")
                    .params(m.get("id").asString(), m.get("name").asString(), m.get("mcc").asString()).update();
        }
        for (JsonNode c : seed.get("customers")) {
            jdbc.sql("INSERT INTO customers (id, name, email, country, created_at) VALUES (?, ?, ?, ?, ?)")
                    .params(c.get("id").asString(), c.get("name").asString(), c.get("email").asString(),
                            c.get("country").asString(), Times.parse(c.get("created_at").asString()))
                    .update();
        }
        for (JsonNode t : seed.get("transactions")) {
            jdbc.sql("""
                            INSERT INTO transactions (id, customer_id, merchant_id, amount_minor, currency, created_at,
                                three_ds, device_id, billing_address, shipping_address, order_ref, recurring)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(t.get("id").asString(), t.get("customer_id").asString(), t.get("merchant_id").asString(),
                            t.get("amount_minor").asLong(), t.get("currency").asString(),
                            Times.parse(t.get("created_at").asString()), t.get("three_ds").asString(),
                            t.get("device_id").asString(), t.get("billing_address").asString(),
                            t.get("shipping_address").asString(), t.get("order_ref").asString(),
                            t.get("recurring").asBoolean())
                    .update();
        }
        for (JsonNode e : seed.get("evidence")) {
            JsonNode note = e.get("merchant_note");
            jdbc.sql("""
                            INSERT INTO merchant_evidence (id, transaction_id, kind, payload, merchant_note, submitted_at)
                            VALUES (?, ?, ?, ?::jsonb, ?, ?)""")
                    .params(e.get("id").asString(), e.get("transaction_id").asString(), e.get("kind").asString(),
                            json.write(e.get("payload")), note == null || note.isNull() ? null : note.asString(),
                            Times.parse(e.get("submitted_at").asString()))
                    .update();
        }
        for (JsonNode d : seed.get("disputes")) {
            store.insertDispute(d.get("id").asString(), d.get("transaction_id").asString(),
                    d.get("reason_code").asString(), d.get("amount_minor").asLong(), d.get("currency").asString(),
                    d.get("cardholder_message").asString(), Times.parse(d.get("opened_at").asString()),
                    DisputeStatus.valueOf(d.get("status").asString()));
        }
    }
}
