package com.serverping.target;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for probe history.
 *
 * <p>Ordered by {@code id} rather than {@code checked_at}: ids are identity-incrementing, so they
 * are strictly unique and cannot collide when several probes land in the same microsecond.
 * Ordering by a nullable millisecond column would not have that guarantee.
 *
 * <p>Both access paths are covered by Flyway migrations rather than annotations — see
 * {@code V2__history_indexes.sql}.
 */
public interface CheckRepository extends JpaRepository<Check, Long> {

    List<Check> findByTargetIdOrderByIdDesc(long targetId, Pageable pageable);

    /**
     * Deletes everything recorded before {@code cutoff}, which is how history is kept from
     * growing without bound.
     *
     * <p>A bulk delete rather than loading rows and removing them one at a time: retention
     * exists to keep this table small, so materialising the rows to be removed would defeat the
     * purpose at exactly the moment it matters.
     *
     * <p>{@code clearAutomatically} drops the rows from the persistence context afterwards.
     * Harmless in the sweeper's own transaction, but without it a longer-running context would
     * keep serving entities that no longer exist.
     *
     * <p>{@code @Transactional} sits <em>here</em> rather than only on the sweeper. A modifying
     * query with no active transaction throws outright, and the sweeper's own annotation is only
     * ever honoured through its Spring proxy — {@code new RetentionSweeper(...)} in a test bypasses
     * that entirely. Anchoring it on the repository method makes the invariant hold for every
     * caller, scheduled or direct.
     *
     * @param cutoff exclusive upper bound — rows strictly older than this are removed
     * @return how many rows were deleted
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from Check c where c.checkedAt < :cutoff")
    int deleteCheckedBefore(@Param("cutoff") Instant cutoff);
}
