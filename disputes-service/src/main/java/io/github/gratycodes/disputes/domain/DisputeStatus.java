package io.github.gratycodes.disputes.domain;

public enum DisputeStatus {
    /** New, or new information arrived; waiting for the agent. */
    RECEIVED,
    /** The agent wrote a recommendation; waiting for a human. */
    AWAITING_REVIEW,
    /** Human accepted: the cardholder keeps the refund. Terminal. */
    ACCEPTED,
    /** Human chose to contest; waiting for the issuer's outcome. */
    REPRESENTED,
    /** Human asked for more information. */
    INFO_REQUESTED,
    /** Representment won: funds return to the merchant. Terminal. */
    CLOSED_WON,
    /** Representment lost: the cardholder keeps the refund. Terminal. */
    CLOSED_LOST;

    public boolean isTerminal() {
        return this == ACCEPTED || this == CLOSED_WON || this == CLOSED_LOST;
    }
}
