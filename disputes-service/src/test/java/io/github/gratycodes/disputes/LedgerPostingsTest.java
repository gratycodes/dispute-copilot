package io.github.gratycodes.disputes;

import static io.github.gratycodes.disputes.domain.DisputeCommand.*;
import static io.github.gratycodes.disputes.domain.DisputeStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gratycodes.disputes.domain.DisputeCommand;
import io.github.gratycodes.disputes.domain.DisputeStateMachine;
import io.github.gratycodes.disputes.domain.DisputeStatus;
import io.github.gratycodes.disputes.domain.LedgerPostings;
import io.github.gratycodes.disputes.domain.Posting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LedgerPostingsTest {

    @ParameterizedTest
    @EnumSource(DisputeStatus.class)
    void everyStatusProducesBalancedPostings(DisputeStatus s) {
        assertThat(LedgerPostings.balanced(LedgerPostings.forStatus(s, "m1", "c1", 4999, "EUR"))).isTrue();
    }

    @Test
    void openedPostingIsBalanced() {
        assertThat(LedgerPostings.balanced(LedgerPostings.opened("m1", 4999, "EUR"))).isTrue();
    }

    /** Walk every path from RECEIVED to a terminal status; the hold account must net to zero. */
    @Test
    void holdNetsToZeroOnEveryTerminalPath() {
        List<List<DisputeCommand>> paths = List.of(
                List.of(RECOMMEND, DECIDE_ACCEPT),
                List.of(RECOMMEND, RECOMMEND, DECIDE_ACCEPT),
                List.of(RECOMMEND, DECIDE_REPRESENT, CLOSE_WON),
                List.of(RECOMMEND, DECIDE_REPRESENT, CLOSE_LOST),
                List.of(RECOMMEND, DECIDE_REQUEST_INFO, INFO_RECEIVED, RECOMMEND, DECIDE_ACCEPT),
                List.of(RECOMMEND, DECIDE_REQUEST_INFO, INFO_RECEIVED, RECOMMEND, DECIDE_REPRESENT, CLOSE_WON));
        for (List<DisputeCommand> path : paths) {
            List<Posting> all = new ArrayList<>(LedgerPostings.opened("m1", 2500, "EUR"));
            DisputeStatus s = RECEIVED;
            for (DisputeCommand c : path) {
                s = DisputeStateMachine.apply(s, c);
                all.addAll(LedgerPostings.forStatus(s, "m1", "c1", 2500, "EUR"));
            }
            assertThat(s.isTerminal()).as(path.toString()).isTrue();
            Map<String, Long> net = new HashMap<>();
            for (Posting p : all) {
                net.merge(p.account(), p.direction() == Posting.Direction.CREDIT ? p.amountMinor() : -p.amountMinor(), Long::sum);
            }
            assertThat(net.get(LedgerPostings.hold("m1"))).as(path.toString()).isZero();
            assertThat(LedgerPostings.balanced(all)).isTrue();
            long toCardholder = net.getOrDefault(LedgerPostings.cardholderRefunds("c1"), 0L);
            long toMerchant = net.getOrDefault(LedgerPostings.available("m1"), 0L);
            // The merchant's loss is exactly the cardholder's refund: all of it, or none of it.
            assertThat(toCardholder + toMerchant).as(path.toString()).isZero();
            assertThat(toCardholder).as(path.toString())
                    .isEqualTo(path.contains(CLOSE_WON) ? 0L : 2500L);
        }
    }

    @Test
    void nonPositiveAmountsRejected() {
        assertThatThrownBy(() -> new Posting("a", Posting.Direction.DEBIT, 0, "EUR"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
