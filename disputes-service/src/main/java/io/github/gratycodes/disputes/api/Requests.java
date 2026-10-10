package io.github.gratycodes.disputes.api;

import io.github.gratycodes.disputes.domain.Decision;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** Request bodies. JSON uses snake_case (spring.jackson.property-naming-strategy). */
public final class Requests {

    private Requests() {}

    public record CreateDispute(
            @NotBlank String transactionId,
            @NotBlank @Pattern(regexp = "FRAUD_CNP|ITEM_NOT_RECEIVED|NOT_AS_DESCRIBED|DUPLICATE|CREDIT_NOT_PROCESSED|CANCELLED_RECURRING")
            String reasonCode,
            @NotBlank @Size(max = 4000) String cardholderMessage) {}

    public record Recommendation(
            @NotBlank @Size(max = 64) String agentVersion,
            @Size(max = 128) String model,
            @NotNull Decision decision,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
            @Pattern(regexp = "P\\d+\\.\\d+") String policyRule,
            @NotNull @Size(max = 2000) String rationale,
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 64) String> citations,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 64) String> flags,
            @Size(max = 64) String traceId,
            Map<String, Object> trace,
            Map<String, Object> usage) {}

    public record DecisionRequest(
            @NotBlank String recommendationId,
            @NotNull Decision decision,
            @Size(max = 2000) String reason,
            @NotBlank @Size(max = 128) String reviewer) {}

    public record Info(@NotBlank @Size(max = 4000) String message) {}

    public record Outcome(@NotNull @Pattern(regexp = "WON|LOST") String outcome) {}
}
