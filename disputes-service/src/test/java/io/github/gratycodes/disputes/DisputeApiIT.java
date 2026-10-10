package io.github.gratycodes.disputes;

import static io.github.gratycodes.disputes.ItSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gratycodes.disputes.outbox.EventPublisher;
import io.github.gratycodes.disputes.outbox.OutboxRelay;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${IT_DB_URL:jdbc:postgresql://localhost:5432/disputes}",
        "spring.flyway.clean-disabled=false",
        "copilot.seed.path=../data/seed.json",
        "copilot.outbox.publisher=none",
        "copilot.outbox.initial-delay=1h",
        "copilot.outbox.poll-interval=1h",
})
@Import({ItSupport.CleanDatabase.class, DisputeApiIT.Recording.class})
class DisputeApiIT {

    /** Publisher that records messages and can be told to fail once. */
    static class RecordingPublisher implements EventPublisher {
        final List<String[]> sent = new CopyOnWriteArrayList<>();
        final AtomicBoolean failNext = new AtomicBoolean();

        @Override
        public void publish(String topic, String key, String payload) throws Exception {
            if (failNext.getAndSet(false)) {
                throw new Exception("broker unavailable");
            }
            sent.add(new String[] {topic, key, payload});
        }
    }

    @TestConfiguration
    static class Recording {
        @Bean
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OutboxRelay relay;

    @Autowired
    RecordingPublisher publisher;

    private String newDispute() {
        Res r = post(port, "/api/disputes", Map.of("transaction_id", "txn_00001",
                "reason_code", "ITEM_NOT_RECEIVED", "cardholder_message", "never arrived " + UUID.randomUUID()));
        assertThat(r.status()).isEqualTo(201);
        return r.body().get("id").asString();
    }

    private String recommend(String id, String decision) {
        Res r = post(port, "/api/disputes/" + id + "/recommendations", recommendation(decision, 0.8));
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body().get("id").asString();
    }

    private Res decide(String id, String recId, String decision, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("recommendation_id", recId);
        body.put("decision", decision);
        body.put("reason", reason);
        body.put("reviewer", "analyst@example.test");
        return post(port, "/api/disputes/" + id + "/decision", body);
    }

    private String status(String id) {
        return get(port, "/api/disputes/" + id).body().get("status").asString();
    }

    private List<String> eventTypes(String id) {
        List<String> out = new ArrayList<>();
        get(port, "/api/disputes/" + id + "/events").body().forEach(e -> out.add(e.get("type").asString()));
        return out;
    }

    @Test
    void seededReadModels() {
        Res d = get(port, "/api/disputes/dsp_0001");
        assertThat(d.status()).isEqualTo(200);
        assertThat(d.body().get("merchant").get("name").asString()).isNotBlank();
        assertThat(get(port, "/api/disputes/dsp_nope").status()).isEqualTo(404);
        assertThat(get(port, "/api/transactions/txn_nope/evidence").status()).isEqualTo(404);
        Res list = get(port, "/api/disputes?status=ACCEPTED");
        assertThat(list.body().get("items").size()).isEqualTo(9);
        assertThat(list.body().get("counts").get("ACCEPTED").asInt()).isEqualTo(9);
    }

    @Test
    void repeatDisputerHistoryCountsRecentDisputes() {
        // Seeded disputes have numeric ids; the repeat-disputer scenario gives one customer three
        // accepted disputes plus one new one.
        String id = jdbc.sql("""
                        SELECT d.id FROM disputes d JOIN transactions t ON t.id = d.transaction_id
                        WHERE d.status = 'RECEIVED' AND d.id ~ '^dsp_[0-9]+$' AND t.customer_id IN (
                          SELECT t2.customer_id FROM disputes d2 JOIN transactions t2 ON t2.id = d2.transaction_id
                          WHERE d2.status = 'ACCEPTED' AND d2.id ~ '^dsp_[0-9]+$'
                          GROUP BY t2.customer_id HAVING count(*) = 3)
                        ORDER BY d.id LIMIT 1""").query(String.class).single();
        JsonNode h = get(port, "/api/disputes/" + id + "/customer-history").body();
        assertThat(h.get("other_disputes_last_180d").asInt()).isEqualTo(3);
        assertThat(h.get("other_disputes").size()).isEqualTo(3);
    }

    @Test
    void createIsIdempotent() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("transaction_id", "txn_00003", "reason_code", "DUPLICATE",
                "cardholder_message", "charged twice");
        Res first = post(port, "/api/disputes", body, key);
        Res retry = post(port, "/api/disputes", body, key);
        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.body().get("id").asString()).isEqualTo(first.body().get("id").asString());
        assertThat(retry.header("Idempotent-Replayed")).isEqualTo("true");

        Res changed = post(port, "/api/disputes", Map.of("transaction_id", "txn_00003", "reason_code", "DUPLICATE",
                "cardholder_message", "different text"), key);
        assertThat(changed.status()).isEqualTo(422);
        assertThat(post(port, "/api/disputes", body, null).status()).isEqualTo(400);

        String id = first.body().get("id").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM disputes WHERE transaction_id = 'txn_00003' AND cardholder_message = 'charged twice'")
                .query(Integer.class).single()).isEqualTo(1);
        JsonNode opened = get(port, "/api/disputes/" + id + "/events").body().get(0);
        assertThat(opened.get("type").asString()).isEqualTo("DisputeOpened");
        assertThat(opened.get("payload").get("postings").size()).isEqualTo(2);
    }

    @Test
    void createValidatesInput() {
        assertThat(post(port, "/api/disputes", Map.of("transaction_id", "txn_00001", "reason_code", "BOGUS",
                "cardholder_message", "x")).status()).isEqualTo(400);
        assertThat(post(port, "/api/disputes", Map.of("transaction_id", "txn_missing", "reason_code", "DUPLICATE",
                "cardholder_message", "x")).status()).isEqualTo(404);
    }

    @Test
    void recommendationNeverDecides() {
        String id = newDispute();
        post(port, "/api/disputes/" + id + "/recommendations", recommendation("ACCEPT", 1.0));
        assertThat(status(id)).isEqualTo("AWAITING_REVIEW");
        assertThat(eventTypes(id)).containsExactly("DisputeOpened", "RecommendationAdded");
        assertThat(jdbc.sql("SELECT count(*) FROM decisions WHERE dispute_id = ?").param(id)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void recommendationValidation() {
        String id = newDispute();
        Map<String, Object> bad = new HashMap<>(recommendation("ACCEPT", 0.5));
        bad.put("confidence", 1.5);
        assertThat(post(port, "/api/disputes/" + id + "/recommendations", bad).status()).isEqualTo(400);
        bad = new HashMap<>(recommendation("REFUND_NOW", 0.5));
        assertThat(post(port, "/api/disputes/" + id + "/recommendations", bad).status()).isEqualTo(400);
        assertThat(status(id)).isEqualTo("RECEIVED");
    }

    @Test
    void approvingRecordsAgreementAndPostings() {
        String id = newDispute();
        String rec = recommend(id, "ACCEPT");
        Res r = decide(id, rec, "ACCEPT", null);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("agreed").asBoolean()).isTrue();
        assertThat(status(id)).isEqualTo("ACCEPTED");
        JsonNode last = get(port, "/api/disputes/" + id + "/events").body().get(2);
        assertThat(last.get("type").asString()).isEqualTo("DecisionRecorded");
        assertThat(last.get("payload").get("postings").get(1).get("account").asString()).startsWith("cardholder:");
    }

    @Test
    void overrideNeedsAReasonAndBecomesALabel() {
        String id = newDispute();
        String rec = recommend(id, "ACCEPT");
        assertThat(decide(id, rec, "REPRESENT", "no").status()).isEqualTo(422);
        assertThat(status(id)).isEqualTo("AWAITING_REVIEW");
        Res r = decide(id, rec, "REPRESENT", "Signed delivery photo on file from the carrier.");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("agreed").asBoolean()).isFalse();
        JsonNode labels = get(port, "/api/labels?overrides_only=true").body();
        JsonNode mine = null;
        for (JsonNode l : labels) {
            if (l.get("dispute_id").asString().equals(id)) {
                mine = l;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("agent_decision").asString()).isEqualTo("ACCEPT");
        assertThat(mine.get("final_decision").asString()).isEqualTo("REPRESENT");
        assertThat(mine.get("reason").asString()).contains("Signed delivery");
    }

    @Test
    void staleRecommendationIsRejected() {
        String id = newDispute();
        String older = recommend(id, "ACCEPT");
        String newer = recommend(id, "REPRESENT");
        Res r = decide(id, older, "ACCEPT", null);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().get("detail").asString()).contains(newer);
    }

    @Test
    void illegalTransitionsAre409() {
        String id = newDispute();
        Res r = decide(id, "rec_x", "ACCEPT", null);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().get("status_before").asString()).isEqualTo("RECEIVED");
        assertThat(post(port, "/api/disputes/" + id + "/outcome", Map.of("outcome", "WON")).status()).isEqualTo(409);
        assertThat(post(port, "/api/disputes/" + id + "/info", Map.of("message", "x")).status()).isEqualTo(409);
    }

    @Test
    void requestInfoLoopsBackToTheAgent() {
        String id = newDispute();
        String rec = recommend(id, "REQUEST_INFO");
        assertThat(decide(id, rec, "REQUEST_INFO", null).status()).isEqualTo(201);
        assertThat(status(id)).isEqualTo("INFO_REQUESTED");
        Res info = post(port, "/api/disputes/" + id + "/info", Map.of("message", "Parcel turned up damaged."));
        assertThat(info.status()).isEqualTo(200);
        assertThat(info.body().get("status").asString()).isEqualTo("RECEIVED");
        assertThat(info.body().get("cardholder_message").asString()).contains("Parcel turned up damaged.");
        recommend(id, "REPRESENT");
        assertThat(status(id)).isEqualTo("AWAITING_REVIEW");
    }

    @Test
    void representThenCloseKeepsAContiguousBalancedLog() {
        String id = newDispute();
        String rec = recommend(id, "REPRESENT");
        decide(id, rec, "REPRESENT", null);
        Res closed = post(port, "/api/disputes/" + id + "/outcome", Map.of("outcome", "WON"));
        assertThat(closed.body().get("status").asString()).isEqualTo("CLOSED_WON");
        JsonNode events = get(port, "/api/disputes/" + id + "/events").body();
        Map<String, Long> net = new HashMap<>();
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).get("seq").asInt()).isEqualTo(i + 1);
            JsonNode postings = events.get(i).get("payload").get("postings");
            if (postings != null) {
                postings.forEach(p -> net.merge(p.get("account").asString(),
                        ("CREDIT".equals(p.get("direction").asString()) ? 1 : -1) * p.get("amount_minor").asLong(), Long::sum));
            }
        }
        assertThat(net.values()).allMatch(v -> v == 0L);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE message_key = ?").param(id).query(Integer.class).single())
                .isEqualTo(events.size());
    }

    @Test
    void concurrentDecisionsOnlyOneWins() throws Exception {
        String id = newDispute();
        String rec = recommend(id, "ACCEPT");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String decision = i % 2 == 0 ? "ACCEPT" : "REQUEST_INFO";
            results.add(pool.submit(() -> {
                go.await();
                return decide(id, rec, decision, "Reviewer two disagrees with the agent here.").status();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(Collections.frequency(statuses, 201)).isEqualTo(1);
        assertThat(Collections.frequency(statuses, 409)).isEqualTo(threads - 1);
        assertThat(jdbc.sql("SELECT count(*) FROM decisions WHERE dispute_id = ?").param(id)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void traceIsServedForTheConsole() {
        String id = newDispute();
        String rec = recommend(id, "ACCEPT");
        Res t = get(port, "/api/recommendations/" + rec + "/trace");
        assertThat(t.status()).isEqualTo(200);
        assertThat(t.body().has("resourceSpans")).isTrue();
        assertThat(get(port, "/api/disputes/" + id + "/recommendations").body().get(0).get("has_trace").asBoolean()).isTrue();
    }

    @Test
    void outboxRelayPublishesInOrderAndRetriesAfterAFailure() {
        newDispute();
        int pending = relay.pending();
        assertThat(pending).isPositive();
        publisher.sent.clear();
        publisher.failNext.set(true);
        assertThat(relay.relayOnce()).isZero();
        assertThat(relay.pending()).isEqualTo(pending);
        assertThat(jdbc.sql("SELECT max(attempts) FROM outbox").query(Integer.class).single()).isEqualTo(1);

        int published = 0;
        while (relay.pending() > 0) {
            published += relay.relayOnce();
        }
        assertThat(published).isEqualTo(pending);
        assertThat(relay.relayOnce()).isZero();
        Map<String, Integer> lastSeq = new HashMap<>();
        for (String[] m : publisher.sent) {
            JsonNode env = JSON.readTree(m[2]);
            assertThat(m[0]).isEqualTo("dispute-events");
            assertThat(env.get("event_id").asString()).isEqualTo(m[1] + "/" + env.get("seq").asInt());
            int prev = lastSeq.getOrDefault(m[1], 0);
            assertThat(env.get("seq").asInt()).as("per-dispute order").isEqualTo(prev + 1);
            lastSeq.put(m[1], env.get("seq").asInt());
        }
    }
}
