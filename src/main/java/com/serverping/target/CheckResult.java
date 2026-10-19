package com.serverping.target;

/**
 * Outcome of a single TCP probe: did the handshake complete, how long it took, and if not,
 * why not.
 *
 * <p>{@code detail} is {@code null} on success. On failure it is one of the reason constants
 * below, which is what lets callers tell "your service is down" apart from "we never reached
 * your network" — a distinction a plain UP/DOWN flag throws away.
 */
public record CheckResult(Status status, long latencyMs, String detail) {

    /** The name did not resolve — the target does not exist in DNS. */
    public static final String DNS_FAILURE = "DNS_FAILURE";

    /** The host answered but nothing is listening on that port. */
    public static final String REFUSED = "REFUSED";

    /** Nothing answered within the timeout — host down, or a firewall is dropping packets. */
    public static final String TIMEOUT = "TIMEOUT";

    /** Resolved to a private or reserved address, so we refused to connect. */
    public static final String BLOCKED = "BLOCKED";

    /** Any other I/O failure; the exception class name is appended. */
    public static final String ERROR = "ERROR";

    /** {@code latencyMs} is the measured handshake time. */
    public static CheckResult up(long latencyMs) {
        return new CheckResult(Status.UP, latencyMs, null);
    }

    /** {@code latencyMs} is the elapsed time before we gave up (e.g. the full timeout). */
    public static CheckResult down(String detail, long latencyMs) {
        return new CheckResult(Status.DOWN, latencyMs, detail);
    }
}
