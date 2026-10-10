package io.github.gratycodes.disputes.api;

import io.github.gratycodes.disputes.domain.DisputeStatus;
import io.github.gratycodes.disputes.persistence.DisputeStore;
import io.github.gratycodes.disputes.persistence.EventLog;
import io.github.gratycodes.disputes.persistence.LookupQueries;
import io.github.gratycodes.disputes.service.DisputeService;
import io.github.gratycodes.disputes.service.NotFoundException;
import io.github.gratycodes.idempotency.Idempotent;
import io.github.gratycodes.idempotency.Idempotent.KeyRequirement;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DisputeController {

    private final DisputeService service;
    private final DisputeStore store;
    private final LookupQueries lookups;
    private final EventLog events;

    public DisputeController(DisputeService service, DisputeStore store, LookupQueries lookups, EventLog events) {
        this.service = service;
        this.store = store;
        this.lookups = lookups;
        this.events = events;
    }

    // ---- lifecycle (all POSTs need an Idempotency-Key) --------------------------------------

    @PostMapping("/disputes")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(key = KeyRequirement.REQUIRED)
    public Map<String, Object> create(@Valid @RequestBody Requests.CreateDispute req) {
        return service.create(req);
    }

    @PostMapping("/disputes/{id}/recommendations")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(key = KeyRequirement.REQUIRED)
    public Map<String, Object> recommend(@PathVariable String id, @Valid @RequestBody Requests.Recommendation req) {
        return service.recommend(id, req);
    }

    @PostMapping("/disputes/{id}/decision")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(key = KeyRequirement.REQUIRED)
    public Map<String, Object> decide(@PathVariable String id, @Valid @RequestBody Requests.DecisionRequest req) {
        return service.decide(id, req);
    }

    @PostMapping("/disputes/{id}/info")
    @Idempotent(key = KeyRequirement.REQUIRED)
    public Map<String, Object> info(@PathVariable String id, @Valid @RequestBody Requests.Info req) {
        return service.provideInfo(id, req);
    }

    @PostMapping("/disputes/{id}/outcome")
    @Idempotent(key = KeyRequirement.REQUIRED)
    public Map<String, Object> outcome(@PathVariable String id, @Valid @RequestBody Requests.Outcome req) {
        return service.close(id, req);
    }

    // ---- review console reads ---------------------------------------------------------------

    @GetMapping("/disputes")
    public Map<String, Object> list(@RequestParam(required = false) DisputeStatus status,
                                    @RequestParam(defaultValue = "200") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", store.list(status == null ? null : status.name(), Math.max(1, Math.min(limit, 500))));
        out.put("counts", store.countsByStatus());
        return out;
    }

    @GetMapping("/disputes/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return lookups.dispute(id);
    }

    @GetMapping("/disputes/{id}/recommendations")
    public List<Map<String, Object>> recommendations(@PathVariable String id) {
        lookups.dispute(id);
        return store.recommendations(id);
    }

    @GetMapping("/disputes/{id}/decisions")
    public List<Map<String, Object>> decisions(@PathVariable String id) {
        lookups.dispute(id);
        return store.decisions(id, false);
    }

    @GetMapping("/disputes/{id}/events")
    public List<Map<String, Object>> events(@PathVariable String id) {
        lookups.dispute(id);
        return events.events(id);
    }

    @GetMapping(value = "/recommendations/{id}/trace", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> trace(@PathVariable String id) {
        String body = store.trace(id).orElseThrow(() -> new NotFoundException("trace for " + id));
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"trace-" + id + ".json\"")
                .body(body);
    }

    /** Human decisions as labels; overrides_only=true gives the cases where the reviewer disagreed. */
    @GetMapping("/labels")
    public List<Map<String, Object>> labels(@RequestParam(name = "overrides_only", defaultValue = "false") boolean overridesOnly) {
        return store.decisions(null, overridesOnly);
    }

    // ---- read models for the agent's MCP tools ---------------------------------------------

    @GetMapping("/transactions/{id}")
    public Map<String, Object> transaction(@PathVariable String id) {
        return lookups.transaction(id);
    }

    @GetMapping("/transactions/{id}/evidence")
    public List<Map<String, Object>> evidence(@PathVariable String id) {
        return lookups.evidence(id);
    }

    @GetMapping("/disputes/{id}/customer-history")
    public Map<String, Object> history(@PathVariable String id) {
        return lookups.customerHistory(id);
    }
}
