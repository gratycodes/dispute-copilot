package io.github.gratycodes.disputes.service;

/** Business-rule validation failure, e.g. an override without a reason. */
public class UnprocessableException extends RuntimeException {
    public UnprocessableException(String message) {
        super(message);
    }
}
