package com.serverping.target;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Target}. Spring Data derives the implementations from these
 * signatures — no SQL, no implementation class.
 */
public interface TargetRepository extends JpaRepository<Target, Long> {

    /** Targets the background scheduler should visit. */
    List<Target> findByEnabledTrue();
}
