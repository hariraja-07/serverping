package com.serverping.target;

/**
 * How a target is checked.
 *
 * <p>TCP is the only protocol implemented in v1: an unprivileged socket connect whose
 * elapsed time is the measured latency. HTTP (status-code aware) and ICMP are deliberately
 * absent — ICMP needs root ({@code CAP_NET_RAW}) and the JVM has no ICMP API, while many
 * production hosts drop ICMP entirely and would report a healthy service as DOWN.
 */
public enum Protocol {

    /** Open a TCP connection to {@code host:port}; success means the service is listening. */
    TCP
}
