package com.serverping.target;

import java.time.Instant;

/**
 * Result of {@code POST /api/v1/targets/{id}/check} — what the probe saw, and when.
 *
 * <p>On failure {@code latencyMs} is still populated: it is the time spent before giving up,
 * so a TIMEOUT reads as roughly the configured timeout rather than a meaningless zero.
 */
public record CheckResponse(
        long targetId,
        Status status,
        long latencyMs,
        String detail,
        Instant checkedAt) {
}
