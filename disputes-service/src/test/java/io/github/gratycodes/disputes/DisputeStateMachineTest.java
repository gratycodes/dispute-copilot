package io.github.gratycodes.disputes;

import static io.github.gratycodes.disputes.domain.DisputeCommand.*;
import static io.github.gratycodes.disputes.domain.DisputeStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gratycodes.disputes.domain.Decision;
import io.github.gratycodes.disputes.domain.DisputeCommand;
import io.github.gratycodes.disputes.domain.DisputeStateMachine;
import io.github.gratycodes.disputes.domain.DisputeStatus;
import io.github.gratycodes.disputes.domain.IllegalTransitionException;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class DisputeStateMachineTest {

    @ParameterizedTest
    @CsvSource({
        "RECEIVED, RECOMMEND, AWAITING_REVIEW",
        "AWAITING_REVIEW, RECOMMEND, AWAITING_REVIEW",
        "AWAITING_REVIEW, DECIDE_ACCEPT, ACCEPTED",
        "AWAITING_REVIEW, DECIDE_REPRESENT, REPRESENTED",
        "AWAITING_REVIEW, DECIDE_REQUEST_INFO, INFO_REQUESTED",
        "INFO_REQUESTED, INFO_RECEIVED, RECEIVED",
        "REPRESENTED, CLOSE_WON, CLOSED_WON",
        "REPRESENTED, CLOSE_LOST, CLOSED_LOST",
    })
    void allowedTransitions(DisputeStatus from, DisputeCommand cmd, DisputeStatus to) {
        assertThat(DisputeStateMachine.apply(from, cmd)).isEqualTo(to);
    }

    @Test
    void exactlyEightTransitionsExist() {
        int allowed = 0;
        for (DisputeStatus s : DisputeStatus.values()) {
            for (DisputeCommand c : DisputeCommand.values()) {
                if (DisputeStateMachine.next(s, c).isPresent()) {
                    allowed++;
                }
            }
        }
        assertThat(allowed).isEqualTo(8);
    }

    @Test
    void humanDecisionIsNeededBeforeAnyMoneyMovingStatus() {
        // Starting from RECEIVED, the agent's only command can never reach a terminal or
        // representment status on its own.
        DisputeStatus s = RECEIVED;
        for (int i = 0; i < 5; i++) {
            s = DisputeStateMachine.apply(s, RECOMMEND);
            assertThat(s).isEqualTo(AWAITING_REVIEW);
        }
    }

    @ParameterizedTest
    @EnumSource(value = DisputeStatus.class, names = {"ACCEPTED", "CLOSED_WON", "CLOSED_LOST"})
    void terminalStatusesAcceptNothing(DisputeStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (DisputeCommand c : DisputeCommand.values()) {
            assertThat(DisputeStateMachine.next(terminal, c)).isEmpty();
        }
    }

    @Test
    void cannotDecideWithoutARecommendation() {
        assertThatThrownBy(() -> DisputeStateMachine.apply(RECEIVED, DECIDE_ACCEPT))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("RECEIVED");
    }

    @Test
    void cannotRecommendAfterTheHumanDecided() {
        Set<DisputeStatus> decided = EnumSet.of(ACCEPTED, REPRESENTED, INFO_REQUESTED, CLOSED_WON, CLOSED_LOST);
        for (DisputeStatus s : decided) {
            assertThat(DisputeStateMachine.next(s, RECOMMEND)).as(s.name()).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(Decision.class)
    void everyDecisionMapsToACommand(Decision d) {
        assertThat(DisputeStateMachine.next(AWAITING_REVIEW, DisputeCommand.decide(d))).isPresent();
    }
}
