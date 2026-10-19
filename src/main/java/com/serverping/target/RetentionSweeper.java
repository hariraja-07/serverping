package com.serverping.target;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps probe history bounded by deleting rows older than {@code app.retention.days}.
 *
 * <p><strong>Why this has to exist.</strong> Nothing else prunes {@code target_check}: every sweep
 * appends one row per enabled target, which at the default 60s interval is 1,440 rows/day/target
 * — about half a million per target per year. A monitoring service that forgets its own history
 * on a schedule it cannot justify is the failure mode here, not the disk.
 *
 * <p><strong>Why hourly, and why an index.</strong> Pruning in small range deletes against
 * {@code idx_target_check_checked_at} keeps each run cheap and the table never far from its
 * steady-state size. A daily job would do the same total work in one visible spike and let the
 * table grow an extra day in between. The index matters more than the cadence: without it every
 * run is a full scan of the very table this exists to keep small.
 *
 * <p><strong>Why {@code fixedDelay} needs no overlap guard.</strong> As with {@link Scheduler},
 * the delay is measured from the end of one run, so a slow prune cannot overlap itself.
 *
 * <p>History outlives its target — {@code target_check} has no foreign key to {@code target} on
 * purpose — so retention is the <em>only* thing that removes rows. A retention window of zero or
 * less turns pruning off entirely and keeps everything, matching the pre-retention behaviour.
 *
 * <p>Disabled in tests via {@code app.retention.enabled=false}, so no background timer can race an
 * assertion; the tests drive {@link #pruneExpiredHistory()} directly.
 */
@Component
@ConditionalOnProperty(name = "app.retention.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweeper.class);

    private final CheckRepository checkRepository;
    private final int retentionDays;

    public RetentionSweeper(CheckRepository checkRepository,
                            @Value("${app.retention.days:30}") int retentionDays) {
        this.checkRepository = checkRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(
            fixedDelayString = "${app.retention.interval-ms:3600000}",
            initialDelayString = "${app.retention.initial-delay-ms:60000}")
    @Transactional
    public void pruneExpiredHistory() {
        if (retentionDays <= 0) {
            // Explicitly "keep everything" rather than a latent bug that silently deletes rows.
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int deleted = checkRepository.deleteCheckedBefore(cutoff);

        // Silent when there is nothing to do: the steady state is a no-op, and a log line every
        // hour saying "deleted 0 rows" tells nobody anything they did not already know.
        if (deleted > 0) {
            log.info("Pruned {} check row(s) older than {} day(s) (before {})",
                    deleted, retentionDays, cutoff);
        }
    }
}
