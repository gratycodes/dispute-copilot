package io.github.gratycodes.disputes.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** For running without Kafka: events are logged and marked published. */
@Component
@ConditionalOnProperty(name = "copilot.outbox.publisher", havingValue = "log", matchIfMissing = true)
public class LogEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LogEventPublisher.class);

    @Override
    public void publish(String topic, String key, String payload) {
        log.info("outbox -> {} key={} {}", topic, key, payload);
    }
}
