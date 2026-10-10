package io.github.gratycodes.disputes.domain;

import io.github.gratycodes.disputes.domain.Posting.Direction;
import java.util.List;

/**
 * Balanced postings for the money movements a dispute implies, in the style of
 * payment-ledger-platform's journal entries. They ride on dispute events; this service does not
 * keep balances itself. A downstream ledger would consume them from Kafka.
 *
 * <ul>
 *   <li>Opened: move the amount from the merchant's available balance into a dispute hold.
 *   <li>Accepted or representment lost: release the hold to the cardholder.
 *   <li>Representment won: release the hold back to the merchant.
 * </ul>
 *
 * Every path to a terminal status nets the hold account to zero (see LedgerPostingsTest).
 */
public final class LedgerPostings {

    private LedgerPostings() {}

    public static String hold(String merchantId) {
        return "merchant:" + merchantId + ":dispute-hold";
    }

    public static String available(String merchantId) {
        return "merchant:" + merchantId + ":available";
    }

    public static String cardholderRefunds(String customerId) {
        return "cardholder:" + customerId + ":refunds";
    }

    public static List<Posting> opened(String merchantId, long amount, String currency) {
        return pair(available(merchantId), hold(merchantId), amount, currency);
    }

    public static List<Posting> forStatus(DisputeStatus status, String merchantId, String customerId,
                                          long amount, String currency) {
        return switch (status) {
            case ACCEPTED, CLOSED_LOST -> pair(hold(merchantId), cardholderRefunds(customerId), amount, currency);
            case CLOSED_WON -> pair(hold(merchantId), available(merchantId), amount, currency);
            default -> List.of();
        };
    }

    private static List<Posting> pair(String debit, String credit, long amount, String currency) {
        return List.of(new Posting(debit, Direction.DEBIT, amount, currency),
                new Posting(credit, Direction.CREDIT, amount, currency));
    }

    public static boolean balanced(List<Posting> postings) {
        long net = 0;
        for (Posting p : postings) {
            net += p.direction() == Direction.DEBIT ? p.amountMinor() : -p.amountMinor();
        }
        return net == 0;
    }
}
