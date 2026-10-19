package com.serverping.target;

/**
 * Fleet-wide counts for {@code GET /api/v1/summary} — one call for a dashboard header instead
 * of downloading every target and counting client-side.
 *
 * <p>{@code avgLatencyMs} averages the <em>latest</em> latency of every target that has been
 * checked at least once, and is {@code null} when nothing has been checked yet (rather than
 * {@code 0}, which would read as "instant"). A DOWN target contributes its time-to-give-up, so
 * a fleet of timeouts pulls the average up — which is the honest reading.
 */
public record SummaryResponse(
        int total,
        int up,
        int down,
        int unknown,
        Long avgLatencyMs) {
}
