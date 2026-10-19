package com.serverping.target;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A host:port that serverping watches.
 *
 * <p>The first block of fields is user-supplied configuration (set via the API); the second
 * block is written by the checker and is read-only to clients.
 */
@Entity
@Table(name = "target")
public class Target {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- configuration (client-writable) -------------------------------------

    /** Human label shown in listings, e.g. {@code "Production API"}. */
    @Column(nullable = false, length = 100)
    private String name;

    /** Hostname or IP the checker connects to. Validated against private ranges on write. */
    @Column(nullable = false, length = 255)
    private String host;

    /** TCP port to connect to. */
    @Column(nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Protocol protocol = Protocol.TCP;

    /** Advisory check cadence in seconds; the scheduler honours a global interval in v1. */
    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds = 60;

    /** Disabled targets are skipped by the scheduler and report {@link Status#UNKNOWN}. */
    @Column(nullable = false)
    private boolean enabled = true;

    // --- observed state (written by the checker) ------------------------------

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.UNKNOWN;

    /** Round-trip time of the TCP handshake, or {@code null} if never checked. */
    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    /** Consecutive failed checks; lets callers distinguish a blip from a sustained outage. */
    @Column(name = "consecutive_fails", nullable = false)
    private int consecutiveFails;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** JPA requires a no-arg constructor. */
    protected Target() {
    }

    public Target(String name, String host, int port) {
        this.name = name;
        this.host = host;
        this.port = port;
    }

    // --- configuration accessors ---------------------------------------------

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(int intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // --- observed-state accessors --------------------------------------------

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(Instant lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public int getConsecutiveFails() {
        return consecutiveFails;
    }

    public void setConsecutiveFails(int consecutiveFails) {
        this.consecutiveFails = consecutiveFails;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "Target{id=" + id + ", name='" + name + "', host='" + host + "', port=" + port
                + ", protocol=" + protocol + ", status=" + status + ", enabled=" + enabled + "}";
    }
}
