package com.serverping.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Sweep behaviour: which targets get visited, and whether one failure costs the others their
 * check.
 *
 * <p>The scheduler itself is switched off for tests ({@code app.scheduler.enabled=false}), so
 * these drive {@link Scheduler#checkAllEnabledTargets()} directly. That keeps the assertions
 * deterministic instead of depending on whether a background tick happened to fire. The full
 * wiring — {@code @EnableScheduling}, the property-driven interval, bean registration — is
 * exercised live against a running application.
 */
@SpringBootTest
class SchedulerTests {

    @Autowired
    private TargetRepository targetRepository;

    @Autowired
    private CheckRepository checkRepository;

    @Autowired
    private TargetService realTargetService;

    @BeforeEach
    void cleanSlate() {
        checkRepository.deleteAll();
        targetRepository.deleteAll();
    }

    @Test
    void sweepVisitsEnabledTargetsAndLeavesDisabledOnesAlone() {
        Target enabled = targetRepository.save(new Target("Enabled", "no-such-host.invalid", 80));
        Target disabled = targetRepository.save(new Target("Disabled", "also-invalid.invalid", 80));
        disabled.setEnabled(false);
        targetRepository.save(disabled);

        new Scheduler(targetRepository, realTargetService).checkAllEnabledTargets();

        Target checked = targetRepository.findById(enabled.getId()).orElseThrow();
        assertThat(checked.getLastCheckedAt()).isNotNull();
        assertThat(checked.getStatus()).isEqualTo(Status.DOWN);
        assertThat(checked.getConsecutiveFails()).isEqualTo(1);

        Target untouched = targetRepository.findById(disabled.getId()).orElseThrow();
        assertThat(untouched.getLastCheckedAt()).isNull();
        assertThat(untouched.getStatus()).isEqualTo(Status.UNKNOWN);

        // Exactly one probe recorded — the disabled target was never dialled.
        assertThat(checkRepository.count()).isEqualTo(1);
    }

    @Test
    void aTargetThatThrowsDoesNotAbortTheSweepForTheOthers() {
        Target healthy = targetRepository.save(new Target("Healthy", "healthy.invalid", 80));
        Target broken = targetRepository.save(new Target("Broken", "broken.invalid", 80));
        Target disabled = targetRepository.save(new Target("Disabled", "off.invalid", 80));
        disabled.setEnabled(false);
        targetRepository.save(disabled);

        TargetService stubbed = mock(TargetService.class);
        when(stubbed.checkNow(broken.getId()))
                .thenThrow(new IllegalStateException("simulated failure"));
        when(stubbed.checkNow(healthy.getId())).thenReturn(
                new CheckResponse(null, healthy.getId(), Status.UP, 12, null, Instant.now()));

        assertThatNoException().isThrownBy(
                () -> new Scheduler(targetRepository, stubbed).checkAllEnabledTargets());

        // Reached despite the other target throwing — the exception must not escape the loop.
        verify(stubbed).checkNow(healthy.getId());
        verify(stubbed).checkNow(broken.getId());
        verify(stubbed, never()).checkNow(disabled.getId());
    }

    @Test
    void sweepOfAnEmptyFleetIsANoOp() {
        assertThatNoException().isThrownBy(
                () -> new Scheduler(targetRepository, realTargetService).checkAllEnabledTargets());
        assertThat(checkRepository.count()).isZero();
    }
}
