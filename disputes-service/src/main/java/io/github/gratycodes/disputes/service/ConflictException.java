package io.github.gratycodes.disputes.service;

/** The request is valid but the dispute has moved on, e.g. a newer recommendation exists. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
