package com.serverping.target;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps every enabled target on a fixed interval, so the API is useful without anyone having
 * to call {@code POST /check} first.
 *
 * <p><strong>Why the loop is per-target and self-healing.</strong> One unreachable host would
 * otherwise throw and abandon the remaining targets for that cycle. Each probe is wrapped
 * independently: a failure is logged and the sweep continues.
 *
 * <p><strong>No overlap guard is needed.</strong> The schedule is {@code fixedDelay}, which
 * measures from the <em>end</em> of one run to the start of the next, so a run cannot overlap
 * itself even if it takes longer than the interval. The consequence worth knowing: with
 * {@code N} targets each allowed {@code app.check-timeout-ms}, a real cycle takes
 * {@code interval + sweep duration}, not {@code interval}.
 *
 * <p>Disabled in tests via {@code app.scheduler.enabled=false}: a background sweep racing
 * test assertions would make failures depend on timing rather than behaviour.
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final TargetRepository targetRepository;
    private final TargetService targetService;

    public Scheduler(TargetRepository targetRepository, TargetService targetService) {
        this.targetRepository = targetRepository;
        this.targetService = targetService;
    }

    @Scheduled(
            fixedDelayString = "${app.check-interval:60000}",
            initialDelayString = "${app.check-initial-delay:5000}")
    public void checkAllEnabledTargets() {
        List<Target> targets = targetRepository.findByEnabledTrue();
        if (targets.isEmpty()) {
            return;
        }

        long startedAt = System.currentTimeMillis();
        int up = 0;
        int down = 0;
        int errored = 0;

        for (Target target : targets) {
            try {
                CheckResponse result = targetService.checkNow(target.getId());
                if (result.status() == Status.UP) {
                    up++;
                } else {
                    down++;
                }
            } catch (Exception e) {
                // One bad target must not cost the rest of the fleet their check.
                errored++;
                log.warn("Scheduled check of target {} ({}:{}) threw: {}",
                        target.getId(), target.getHost(), target.getPort(), e.toString());
            }
        }

        log.info("Checked {} target(s) in {} ms — {} up, {} down{}",
                targets.size(),
                System.currentTimeMillis() - startedAt,
                up,
                down,
                errored == 0 ? "" : ", " + errored + " errored");
    }
}
