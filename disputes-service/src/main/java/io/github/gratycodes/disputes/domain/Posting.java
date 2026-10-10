package io.github.gratycodes.disputes.domain;

/** One side of a double-entry posting carried on a dispute event. Amounts are minor units. */
public record Posting(String account, Direction direction, long amountMinor, String currency) {

    public enum Direction {
        DEBIT,
        CREDIT
    }

    public Posting {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("posting amount must be positive");
        }
    }
}
