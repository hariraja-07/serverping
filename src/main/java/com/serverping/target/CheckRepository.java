package com.serverping.target;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for probe history.
 *
 * <p>Ordered by {@code id} rather than {@code checked_at}: ids are identity-incrementing, so they
 * are strictly unique and cannot collide when several probes land in the same microsecond.
 * Ordering by a nullable millisecond column would not have that guarantee.
 */
public interface CheckRepository extends JpaRepository<Check, Long> {

    List<Check> findByTargetIdOrderByIdDesc(long targetId, Pageable pageable);
}
