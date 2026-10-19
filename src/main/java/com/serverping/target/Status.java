package com.serverping.target;

/**
 * Result of the most recent check against a target.
 */
public enum Status {

    /** The TCP handshake completed within the timeout — host up and service listening. */
    UP,

    /**
     * The connection did not complete: refused, timed out, DNS failure, or socket error.
     * The specific cause is recorded on the {@code Check} row, not here.
     */
    DOWN,

    /** Never checked, or the target is disabled. */
    UNKNOWN
}
