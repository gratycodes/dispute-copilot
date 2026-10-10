package io.github.gratycodes.disputes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plain-HTTP helpers for the integration tests, so requests look exactly like a client's. */
final class ItSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    record Res(int status, JsonNode body, HttpResponse<String> raw) {
        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    private ItSupport() {}

    static Res get(int port, String path) {
        return send(port, "GET", path, null, null);
    }

    static Res post(int port, String path, Object body) {
        return post(port, path, body, UUID.randomUUID().toString());
    }

    static Res post(int port, String path, Object body, String key) {
        return send(port, "POST", path, body, key);
    }

    static Res send(int port, String method, String path, Object body, String key) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Client", "it");
            if (key != null) {
                b.header("Idempotency-Key", key);
            }
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body());
            return new Res(r.statusCode(), node, r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static java.util.Map<String, Object> recommendation(String decision, double confidence) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("agent_version", "it");
        m.put("model", "test-model");
        m.put("decision", decision);
        m.put("confidence", confidence);
        m.put("policy_rule", "P4.1");
        m.put("rationale", "test");
        m.put("citations", List.of("P4.1", "txn_00001"));
        m.put("flags", List.of());
        m.put("trace_id", "0123456789abcdef0123456789abcdef");
        m.put("trace", java.util.Map.of("resourceSpans", List.of()));
        return m;
    }

    /** Start every test run from an empty, freshly migrated schema. */
    @TestConfiguration
    static class CleanDatabase {
        @Bean
        FlywayMigrationStrategy cleanThenMigrate() {
            return (Flyway flyway) -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }
}
