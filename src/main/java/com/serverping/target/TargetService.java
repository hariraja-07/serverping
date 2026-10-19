package com.serverping.target;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * All reads and writes for {@link Target}, and the single place the SSRF guard is invoked so
 * no handler can accidentally skip it.
 *
 * <p>{@code @Transactional} on each public method keeps the unit-of-work explicit — a request
 * either saves its changes or none of them.
 */
@Service
public class TargetService {

    private final TargetRepository targetRepository;
    private final CheckRepository checkRepository;
    private final TargetValidator targetValidator;
    private final CheckService checkService;

    public TargetService(TargetRepository targetRepository, CheckRepository checkRepository,
                         TargetValidator targetValidator, CheckService checkService) {
        this.targetRepository = targetRepository;
        this.checkRepository = checkRepository;
        this.targetValidator = targetValidator;
        this.checkService = checkService;
    }

    @Transactional
    public Target create(TargetRequest request) {
        targetValidator.validate(request.host());

        Target target = new Target(request.name(), request.host(), request.port());
        target.setProtocol(request.protocol() != null ? request.protocol() : Protocol.TCP);
        target.setIntervalSeconds(
                request.intervalSeconds() != null ? request.intervalSeconds() : 60);
        target.setEnabled(request.enabled() == null || request.enabled());

        return targetRepository.save(target);
    }

    @Transactional(readOnly = true)
    public List<Target> list(Status status) {
        if (status != null) {
            return targetRepository.findByStatus(status);
        }
        return targetRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    @Transactional(readOnly = true)
    public Target get(long id) {
        return find(id);
    }

    /** Full replacement: every field in the request wins, including ones the caller omits. */
    @Transactional
    public Target replace(long id, TargetRequest request) {
        targetValidator.validate(request.host());

        Target target = find(id);
        target.setName(request.name());
        target.setHost(request.host());
        target.setPort(request.port());
        target.setProtocol(request.protocol() != null ? request.protocol() : Protocol.TCP);
        target.setIntervalSeconds(
                request.intervalSeconds() != null ? request.intervalSeconds() : 60);
        target.setEnabled(request.enabled() == null || request.enabled());

        return targetRepository.save(target);
    }

    @Transactional
    public void delete(long id) {
        targetRepository.delete(find(id));
    }

    /**
     * Probe one target immediately instead of waiting for the background scheduler, and fold
     * the result back into the target's observed state.
     *
     * <p>A disabled target is still probed when asked explicitly: disabling controls whether the
     * <em>scheduler</em> visits it, and refusing a deliberate request would just be surprising.
     *
     * <p>{@code consecutiveFails} resets to zero on success so callers can distinguish a single
     * blip (flapping) from a sustained outage.
     */
    @Transactional
    public CheckResponse checkNow(long id) {
        Target target = find(id);

        CheckResult result = checkService.check(target.getHost(), target.getPort());
        Instant checkedAt = Instant.now();

        target.setStatus(result.status());
        target.setLatencyMs(result.latencyMs());
        target.setLastCheckedAt(checkedAt);
        target.setConsecutiveFails(result.status() == Status.UP
                ? 0
                : target.getConsecutiveFails() + 1);
        targetRepository.save(target);

        // History is written in the same transaction as the target update, so a probe is never
        // recorded twice or half-way. This is what survives a restart, where Target only keeps
        // the latest result.
        Check record = checkRepository.save(new Check(target.getId(), result.status(),
                result.latencyMs(), result.detail(), checkedAt));

        return new CheckResponse(record.getId(), target.getId(), result.status(),
                result.latencyMs(), result.detail(), checkedAt);
    }

    /** Most recent probes for one target, newest first. 404s if the target does not exist. */
    @Transactional(readOnly = true)
    public List<CheckResponse> history(long targetId, int limit) {
        find(targetId);

        return checkRepository
                .findByTargetIdOrderByIdDesc(targetId, PageRequest.of(0, limit))
                .stream()
                .map(check -> new CheckResponse(check.getId(), check.getTargetId(),
                        check.getStatus(), check.getLatencyMs(), check.getDetail(),
                        check.getCheckedAt()))
                .toList();
    }

    /** Counts plus average latency across the fleet, computed in a single pass over the targets. */
    @Transactional(readOnly = true)
    public SummaryResponse summary() {
        List<Target> targets = targetRepository.findAll();

        int up = 0;
        int down = 0;
        int unknown = 0;
        long latencyTotal = 0;
        int latencySamples = 0;

        for (Target target : targets) {
            switch (target.getStatus()) {
                case UP -> up++;
                case DOWN -> down++;
                case UNKNOWN -> unknown++;
            }
            if (target.getLatencyMs() != null) {
                latencyTotal += target.getLatencyMs();
                latencySamples++;
            }
        }

        Long average = latencySamples > 0
                ? Math.round((double) latencyTotal / latencySamples)
                : null;

        return new SummaryResponse(targets.size(), up, down, unknown, average);
    }

    private Target find(long id) {
        return targetRepository.findById(id)
                .orElseThrow(() -> new TargetNotFoundException(id));
    }
}
