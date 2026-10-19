package com.serverping.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Retention: what gets deleted, what must survive, and what must not be deletable at all.
 *
 * <p>The sweeper is switched off for tests ({@code app.retention.enabled=false}) for the same
 * reason the scheduler is — a background timer deciding which rows exist would make assertions
 * depend on timing rather than behaviour, and here the consequence would be worse than a status
 * flipping mid-test: rows a test expects to find would quietly disappear. These drive
 * {@link RetentionSweeper#pruneExpiredHistory()} directly instead.
 *
 * <p>Row ages are set well clear of the 30-day boundary (29 vs 31 days) rather than sitting on
 * it. The cutoff is computed inside the method from a fresh {@code Instant.now()}, so a row
 * written at exactly the cutoff could go either way depending on nanosecond timing — an assertion
 * there would be testing the clock, not the policy.
 */
@SpringBootTest
class RetentionSweeperTests {

    private static final int WINDOW_DAYS = 30;

    @Autowired
    private CheckRepository checkRepository;

    @Autowired
    private TargetRepository targetRepository;

    private Target target;

    @BeforeEach
    void cleanSlate() {
        checkRepository.deleteAll();
        targetRepository.deleteAll();
        target = targetRepository.save(new Target("Watched", "example.com", 443));
    }

    @Test
    void rowsPastTheWindowAreDeletedAndRecentOnesSurvive() {
        Instant now = Instant.now();
        Check expired = check(target, now.minus(Duration.ofDays(31)));
        Check insideWindow = check(target, now.minus(Duration.ofDays(29)));
        Check fresh = check(target, now);

        new RetentionSweeper(checkRepository, WINDOW_DAYS).pruneExpiredHistory();

        assertThat(checkRepository.findAll())
                .extracting(Check::getId)
                .containsExactlyInAnyOrder(insideWindow.getId(), fresh.getId())
                .doesNotContain(expired.getId());
    }

    @Test
    void pruningSpansEveryTargetRatherThanOne() {
        Target other = targetRepository.save(new Target("Other", "other.example.com", 80));
        Instant now = Instant.now();
        check(target, now.minus(Duration.ofDays(45)));
        check(other, now.minus(Duration.ofDays(45)));
        check(target, now);
        assertThat(checkRepository.count()).isEqualTo(3);

        new RetentionSweeper(checkRepository, WINDOW_DAYS).pruneExpiredHistory();

        // Retention is a property of the history table, not of any one target's schedule.
        assertThat(checkRepository.count()).isOne();
    }

    @Test
    void historyForATargetThatNoLongerExistsIsStillPruned() {
        Instant now = Instant.now();
        // No row in `target` matches this id, and `target_check` deliberately has no foreign key
        // — history outlives its target. So retention must not be reaching for a join here, or
        // exactly the orphaned rows that only it can ever remove would pile up forever.
        checkRepository.save(new Check(999_999L, Status.DOWN, 2000, "TIMEOUT",
                now.minus(Duration.ofDays(60))));
        assertThat(checkRepository.count()).isOne();

        new RetentionSweeper(checkRepository, WINDOW_DAYS).pruneExpiredHistory();

        assertThat(checkRepository.count()).isZero();
    }

    @Test
    void zeroOrNegativeDaysKeepsEverything() {
        Instant now = Instant.now();
        check(target, now.minus(Duration.ofDays(3650)));

        // "Keep it all" must be an explicit, supported setting rather than a misconfiguration
        // that looks like the feature is broken.
        new RetentionSweeper(checkRepository, 0).pruneExpiredHistory();
        assertThat(checkRepository.count()).isOne();

        new RetentionSweeper(checkRepository, -1).pruneExpiredHistory();
        assertThat(checkRepository.count()).isOne();
    }

    @Test
    void runningTwiceIsIdempotent() {
        check(target, Instant.now().minus(Duration.ofDays(90)));
        RetentionSweeper sweeper = new RetentionSweeper(checkRepository, WINDOW_DAYS);

        sweeper.pruneExpiredHistory();
        sweeper.pruneExpiredHistory();

        // Nothing left to delete must be a clean no-op, not an error on the second pass.
        assertThat(checkRepository.count()).isZero();
    }

    private Check check(Target owner, Instant checkedAt) {
        return checkRepository.save(
                new Check(owner.getId(), Status.UP, 12, null, checkedAt));
    }
}
