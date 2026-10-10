package io.github.gratycodes.disputes;

import static io.github.gratycodes.disputes.ItSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gratycodes.disputes.outbox.OutboxRelay;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/** Runs only when a broker is available (the CI integration job starts one). */
@EnabledIfEnvironmentVariable(named = "KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${IT_DB_URL:jdbc:postgresql://localhost:5432/disputes}",
        "spring.flyway.clean-disabled=false",
        "copilot.outbox.publisher=kafka",
        "copilot.outbox.topic=dispute-events-it",
        "copilot.outbox.initial-delay=1h",
        "copilot.outbox.poll-interval=1h",
        "copilot.seed.path=../data/seed.json",
})
@Import(ItSupport.CleanDatabase.class)
class KafkaOutboxIT {

    @LocalServerPort
    int port;

    @Autowired
    OutboxRelay relay;

    @Test
    void eventsReachKafkaInOrderKeyedByDispute() {
        Res created = post(port, "/api/disputes", Map.of("transaction_id", "txn_00001",
                "reason_code", "ITEM_NOT_RECEIVED", "cardholder_message", "kafka " + UUID.randomUUID()));
        String id = created.body().get("id").asString();
        Res rec = post(port, "/api/disputes/" + id + "/recommendations", recommendation("ACCEPT", 0.7));
        post(port, "/api/disputes/" + id + "/decision", Map.of("recommendation_id", rec.body().get("id").asString(),
                "decision", "ACCEPT", "reviewer", "analyst@example.test"));

        while (relay.pending() > 0) {
            assertThat(relay.relayOnce()).as("relay makes progress").isPositive();
        }

        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getenv("KAFKA_BOOTSTRAP_SERVERS"));
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<JsonNode> mine = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(List.of("dispute-events-it"));
            long deadline = System.currentTimeMillis() + 30_000;
            while (mine.size() < 3 && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (id.equals(r.key())) {
                        mine.add(JSON.readTree(r.value()));
                    }
                }
            }
        }
        assertThat(mine).extracting(e -> e.get("type").asString())
                .containsExactly("DisputeOpened", "RecommendationAdded", "DecisionRecorded");
        assertThat(mine).extracting(e -> e.get("seq").asInt()).containsExactly(1, 2, 3);
        assertThat(mine.get(2).get("payload").get("status").asString()).isEqualTo("ACCEPTED");
    }
}
