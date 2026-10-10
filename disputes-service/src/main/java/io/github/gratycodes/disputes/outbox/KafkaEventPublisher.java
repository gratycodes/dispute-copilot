package io.github.gratycodes.disputes.outbox;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Synchronous send with acks=all and an idempotent producer (see application.yaml). */
@Component
@ConditionalOnProperty(name = "copilot.outbox.publisher", havingValue = "kafka")
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafka;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(String topic, String key, String payload) throws Exception {
        kafka.send(topic, key, payload).get(10, TimeUnit.SECONDS);
    }
}
