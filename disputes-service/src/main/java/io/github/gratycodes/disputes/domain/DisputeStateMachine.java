package io.github.gratycodes.disputes.domain;

import static io.github.gratycodes.disputes.domain.DisputeCommand.*;
import static io.github.gratycodes.disputes.domain.DisputeStatus.*;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Allowed lifecycle transitions. The table is the whole policy: anything not listed is rejected.
 *
 * <pre>
 * RECEIVED --RECOMMEND--> AWAITING_REVIEW --RECOMMEND--> AWAITING_REVIEW (newer recommendation)
 * AWAITING_REVIEW --DECIDE_ACCEPT--> ACCEPTED
 * AWAITING_REVIEW --DECIDE_REPRESENT--> REPRESENTED --CLOSE_WON/CLOSE_LOST--> CLOSED_WON/CLOSED_LOST
 * AWAITING_REVIEW --DECIDE_REQUEST_INFO--> INFO_REQUESTED --INFO_RECEIVED--> RECEIVED
 * </pre>
 *
 * Note what is missing: the agent's only command is RECOMMEND, and RECOMMEND never leads to a
 * state where money moves.
 */
public final class DisputeStateMachine {

    private static final Map<DisputeStatus, Map<DisputeCommand, DisputeStatus>> TABLE = new EnumMap<>(DisputeStatus.class);

    static {
        allow(RECEIVED, RECOMMEND, AWAITING_REVIEW);
        allow(AWAITING_REVIEW, RECOMMEND, AWAITING_REVIEW);
        allow(AWAITING_REVIEW, DECIDE_ACCEPT, ACCEPTED);
        allow(AWAITING_REVIEW, DECIDE_REPRESENT, REPRESENTED);
        allow(AWAITING_REVIEW, DECIDE_REQUEST_INFO, INFO_REQUESTED);
        allow(INFO_REQUESTED, INFO_RECEIVED, RECEIVED);
        allow(REPRESENTED, CLOSE_WON, CLOSED_WON);
        allow(REPRESENTED, CLOSE_LOST, CLOSED_LOST);
    }

    private DisputeStateMachine() {}

    private static void allow(DisputeStatus from, DisputeCommand cmd, DisputeStatus to) {
        TABLE.computeIfAbsent(from, k -> new EnumMap<>(DisputeCommand.class)).put(cmd, to);
    }

    public static Optional<DisputeStatus> next(DisputeStatus from, DisputeCommand cmd) {
        return Optional.ofNullable(TABLE.getOrDefault(from, Map.of()).get(cmd));
    }

    public static DisputeStatus apply(DisputeStatus from, DisputeCommand cmd) {
        return next(from, cmd).orElseThrow(() -> new IllegalTransitionException(from, cmd));
    }
}
