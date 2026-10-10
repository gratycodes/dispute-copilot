package io.github.gratycodes.disputes.domain;

public enum DisputeCommand {
    /** Agent attaches a recommendation. */
    RECOMMEND,
    /** Human decides. Only these three commands come from a person. */
    DECIDE_ACCEPT,
    DECIDE_REPRESENT,
    DECIDE_REQUEST_INFO,
    /** Cardholder or merchant sent the requested information. */
    INFO_RECEIVED,
    /** Issuer's ruling on a representment. */
    CLOSE_WON,
    CLOSE_LOST;

    public static DisputeCommand decide(Decision d) {
        return switch (d) {
            case ACCEPT -> DECIDE_ACCEPT;
            case REPRESENT -> DECIDE_REPRESENT;
            case REQUEST_INFO -> DECIDE_REQUEST_INFO;
        };
    }
}
