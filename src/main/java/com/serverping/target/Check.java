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
 * One row per probe — the raw history behind {@code GET /api/v1/targets/{id}/checks}.
 *
 * <p>Kept separate from {@link Target}, which only holds the <em>latest</em> result. Without
 * this table a restart or a single passing check would erase the outage you were investigating.
 *
 * <p>Table is {@code target_check} rather than {@code check}: {@code CHECK} is a SQL reserved
 * word (column constraints) and quoting identifiers differs across databases for no good reason.
 */
@Entity
@Table(name = "target_check")
public class Check {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Denormalised on purpose — the target may be deleted while its history is still worth keeping. */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    /** Elapsed time: handshake time when UP, time-to-give-up when DOWN. */
    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    /** Failure reason from {@link CheckResult}, or {@code null} when the probe succeeded. */
    @Column(length = 64)
    private String detail;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    /** JPA requires a no-arg constructor. */
    protected Check() {
    }

    public Check(Long targetId, Status status, long latencyMs, String detail, Instant checkedAt) {
        this.targetId = targetId;
        this.status = status;
        this.latencyMs = latencyMs;
        this.detail = detail;
        this.checkedAt = checkedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getTargetId() {
        return targetId;
    }

    public Status getStatus() {
        return status;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}
