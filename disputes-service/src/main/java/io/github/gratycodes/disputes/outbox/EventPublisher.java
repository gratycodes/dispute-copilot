package io.github.gratycodes.disputes.outbox;

/** Delivers one outbox message. Must throw if delivery is not confirmed. */
public interface EventPublisher {
    void publish(String topic, String key, String payload) throws Exception;
}
