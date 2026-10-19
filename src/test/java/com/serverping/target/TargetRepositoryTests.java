package com.serverping.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boot 4 no longer ships {@code @DataJpaTest} in the webmvc test starter, so this exercises
 * the repository through the full application context against an in-memory database.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:targettest;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TargetRepositoryTests {

    @Autowired
    private TargetRepository targetRepository;

    @BeforeEach
    void cleanSlate() {
        targetRepository.deleteAll();
    }

    @Test
    void savesAndReloadsATarget() {
        Target target = new Target("GitHub", "api.github.com", 443);

        Target saved = targetRepository.save(target);

        assertThat(saved.getId()).isNotNull();

        Optional<Target> found = targetRepository.findById(saved.getId());
        assertThat(found).isPresent();

        Target reloaded = found.get();
        assertThat(reloaded.getName()).isEqualTo("GitHub");
        assertThat(reloaded.getHost()).isEqualTo("api.github.com");
        assertThat(reloaded.getPort()).isEqualTo(443);
        assertThat(reloaded.getProtocol()).isEqualTo(Protocol.TCP);
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void defaultsAreSaneBeforeAnyCheck() {
        Target target = targetRepository.save(new Target("Fresh", "example.com", 80));

        assertThat(target.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(target.getLatencyMs()).isNull();
        assertThat(target.getLastCheckedAt()).isNull();
        assertThat(target.getConsecutiveFails()).isZero();
        assertThat(target.getIntervalSeconds()).isEqualTo(60);
        assertThat(target.isEnabled()).isTrue();
    }

    @Test
    void schedulerQuerySkipsDisabledTargets() {
        Target enabled = new Target("On", "on.example.com", 443);
        Target disabled = new Target("Off", "off.example.com", 443);
        disabled.setEnabled(false);
        targetRepository.saveAll(List.of(enabled, disabled));

        List<Target> schedulable = targetRepository.findByEnabledTrue();

        assertThat(schedulable)
                .extracting(Target::getName)
                .containsExactly("On");
    }
}
