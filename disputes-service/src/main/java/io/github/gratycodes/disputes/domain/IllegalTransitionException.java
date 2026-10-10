package io.github.gratycodes.disputes.domain;

public class IllegalTransitionException extends RuntimeException {

    private final DisputeStatus from;
    private final DisputeCommand command;

    public IllegalTransitionException(DisputeStatus from, DisputeCommand command) {
        super("cannot " + command + " a dispute in status " + from);
        this.from = from;
        this.command = command;
    }

    public DisputeStatus from() {
        return from;
    }

    public DisputeCommand command() {
        return command;
    }
}
