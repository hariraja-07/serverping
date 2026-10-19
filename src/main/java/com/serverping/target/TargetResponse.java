package com.serverping.target;

import java.time.Instant;

/**
 * Wire representation of a {@link Target}, returned by every target endpoint.
 *
 * <p>A record with a static factory keeps the field list in one place: if the entity gains a
 * field that should be exposed, it is added here once rather than to five handler methods.
 * {@code latencyMs} and {@code lastCheckedAt} are {@code null} until the first check runs.
 */
public record TargetResponse(

        Long id,
        String name,
        String host,
        int port,
        Protocol protocol,
        int intervalSeconds,
        boolean enabled,
        Status status,
        Long latencyMs,
        Instant lastCheckedAt,
        int consecutiveFails,
        Instant createdAt) {

    static TargetResponse from(Target target) {
        return new TargetResponse(
                target.getId(),
                target.getName(),
                target.getHost(),
                target.getPort(),
                target.getProtocol(),
                target.getIntervalSeconds(),
                target.isEnabled(),
                target.getStatus(),
                target.getLatencyMs(),
                target.getLastCheckedAt(),
                target.getConsecutiveFails(),
                target.getCreatedAt());
    }
}
