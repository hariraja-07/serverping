package com.serverping.target;

import java.util.List;

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
    private final TargetValidator targetValidator;

    public TargetService(TargetRepository targetRepository, TargetValidator targetValidator) {
        this.targetRepository = targetRepository;
        this.targetValidator = targetValidator;
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

    private Target find(long id) {
        return targetRepository.findById(id)
                .orElseThrow(() -> new TargetNotFoundException(id));
    }
}
