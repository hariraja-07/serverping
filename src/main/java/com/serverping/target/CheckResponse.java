package com.serverping.target;

import java.time.Instant;

/**
 * Result of {@code POST /api/v1/targets/{id}/check}, and one row of
 * {@code GET /api/v1/targets/{id}/checks} — the same shape in both places, so a client can
 * render an individual probe and a history page from one model.
 *
 * <p>{@code checkId} is the history row this result was persisted as, letting a caller jump
 * straight from "we just checked" to "here is that check" rather than re-querying.
 *
 * <p>On failure {@code latencyMs} is still populated: it is the time spent before giving up,
 * so a TIMEOUT reads as roughly the configured timeout rather than a meaningless zero.
 */
public record CheckResponse(
        Long checkId,
        long targetId,
        Status status,
        long latencyMs,
        String detail,
        Instant checkedAt) {
}
