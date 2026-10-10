package io.github.gratycodes.disputes.service;

import io.github.gratycodes.disputes.api.Requests;
import io.github.gratycodes.disputes.domain.DisputeCommand;
import io.github.gratycodes.disputes.domain.DisputeStateMachine;
import io.github.gratycodes.disputes.domain.DisputeStatus;
import io.github.gratycodes.disputes.domain.LedgerPostings;
import io.github.gratycodes.disputes.domain.Posting;
import io.github.gratycodes.disputes.persistence.DisputeStore;
import io.github.gratycodes.disputes.persistence.EventLog;
import io.github.gratycodes.disputes.persistence.LookupQueries;
import io.github.gratycodes.disputes.persistence.Times;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every state change runs in one transaction that (1) locks the dispute row, (2) checks the
 * transition against {@link DisputeStateMachine}, (3) writes the change, and (4) appends a dispute
 * event and its outbox row. Either all of it commits or none of it does.
 */
@Service
public class DisputeService {

    public static final int MIN_OVERRIDE_REASON = 10;

    private final DisputeStore store;
    private final EventLog events;
    private final LookupQueries lookups;

    public DisputeService(DisputeStore store, EventLog events, LookupQueries lookups) {
        this.store = store;
        this.events = events;
        this.lookups = lookups;
    }

    private static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Transactional
    public Map<String, Object> create(Requests.CreateDispute req) {
        Map<String, Object> txn = store.transactionForNewDispute(req.transactionId())
                .orElseThrow(() -> new NotFoundException("transaction " + req.transactionId()));
        String id = newId("dsp");
        long amount = (Long) txn.get("amount_minor");
        String currency = (String) txn.get("currency");
        store.insertDispute(id, req.transactionId(), req.reasonCode(), amount, currency, req.cardholderMessage(),
                OffsetDateTime.now(ZoneOffset.UTC), DisputeStatus.RECEIVED);
        store.lock(id);
        events.append(id, "DisputeOpened", payload(
                "reason_code", req.reasonCode(), "amount_minor", amount, "currency", currency,
                "postings", LedgerPostings.opened((String) txn.get("merchant_id"), amount, currency)));
        return lookups.dispute(id);
    }

    @Transactional
    public Map<String, Object> recommend(String disputeId, Requests.Recommendation req) {
        DisputeStore.Locked d = store.lock(disputeId);
        DisputeStatus to = DisputeStateMachine.apply(d.status(), DisputeCommand.RECOMMEND);
        String id = newId("rec");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("agent_version", req.agentVersion());
        row.put("model", req.model());
        row.put("decision", req.decision().name());
        row.put("confidence", req.confidence());
        row.put("policy_rule", req.policyRule());
        row.put("rationale", req.rationale());
        row.put("citations", req.citations());
        row.put("flags", req.flags());
        row.put("usage", req.usage());
        row.put("trace_id", req.traceId());
        row.put("trace", req.trace());
        store.insertRecommendation(id, disputeId, row);
        store.setStatus(d, to);
        events.append(disputeId, "RecommendationAdded", payload(
                "recommendation_id", id, "decision", req.decision().name(), "confidence", req.confidence(),
                "agent_version", req.agentVersion(), "flags", req.flags(), "status", to.name()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("dispute_id", disputeId);
        out.put("decision", req.decision().name());
        out.put("status", to.name());
        return out;
    }

    @Transactional
    public Map<String, Object> decide(String disputeId, Requests.DecisionRequest req) {
        DisputeStore.Locked d = store.lock(disputeId);
        DisputeCommand cmd = DisputeCommand.decide(req.decision());
        DisputeStatus to = DisputeStateMachine.apply(d.status(), cmd);
        DisputeStore.Rec latest = store.latestRecommendation(disputeId)
                .orElseThrow(() -> new ConflictException("dispute has no recommendation to review"));
        if (!latest.id().equals(req.recommendationId())) {
            throw new ConflictException("recommendation " + req.recommendationId()
                    + " is not the latest for this dispute (latest is " + latest.id() + ")");
        }
        boolean agreed = latest.decision() == req.decision();
        String reason = req.reason() == null ? null : req.reason().strip();
        if (!agreed && (reason == null || reason.length() < MIN_OVERRIDE_REASON)) {
            throw new UnprocessableException("overriding the recommendation needs a reason of at least "
                    + MIN_OVERRIDE_REASON + " characters");
        }
        String id = newId("dec");
        store.insertDecision(id, disputeId, latest.id(), latest.decision(), req.decision(), agreed,
                reason == null || reason.isEmpty() ? null : reason, req.reviewer());
        store.setStatus(d, to);
        List<Posting> postings = LedgerPostings.forStatus(to, d.merchantId(), d.customerId(), d.amountMinor(), d.currency());
        events.append(disputeId, "DecisionRecorded", payload(
                "decision_id", id, "recommendation_id", latest.id(), "agent_decision", latest.decision().name(),
                "final_decision", req.decision().name(), "agreed", agreed, "reviewer", req.reviewer(),
                "status", to.name(), "postings", postings));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("dispute_id", disputeId);
        out.put("final_decision", req.decision().name());
        out.put("agreed", agreed);
        out.put("status", to.name());
        return out;
    }

    @Transactional
    public Map<String, Object> provideInfo(String disputeId, Requests.Info req) {
        DisputeStore.Locked d = store.lock(disputeId);
        DisputeStatus to = DisputeStateMachine.apply(d.status(), DisputeCommand.INFO_RECEIVED);
        store.appendMessage(disputeId, "\n\n[Additional information, " + Times.iso(Instant.now()) + "]\n" + req.message());
        store.setStatus(d, to);
        events.append(disputeId, "InfoReceived", payload("status", to.name()));
        return lookups.dispute(disputeId);
    }

    @Transactional
    public Map<String, Object> close(String disputeId, Requests.Outcome req) {
        DisputeStore.Locked d = store.lock(disputeId);
        DisputeCommand cmd = "WON".equals(req.outcome()) ? DisputeCommand.CLOSE_WON : DisputeCommand.CLOSE_LOST;
        DisputeStatus to = DisputeStateMachine.apply(d.status(), cmd);
        store.setStatus(d, to);
        events.append(disputeId, "DisputeClosed", payload("outcome", req.outcome(), "status", to.name(),
                "postings", LedgerPostings.forStatus(to, d.merchantId(), d.customerId(), d.amountMinor(), d.currency())));
        return lookups.dispute(disputeId);
    }

    private static Map<String, Object> payload(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
