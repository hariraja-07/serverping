-- Indexes for the two query patterns that reach into history.
--
-- WHY NOW: this table had nothing beyond its primary key, which was fine when it held a
-- handful of test rows and is not fine at the rate it grows — every sweep appends one row per
-- target, so ~1,440 rows/day/target, roughly half a million per target per year. At that size
-- an unindexed lookup is a sequential scan over everything.
--
-- WHY HERE AND NOT IN AN @Table/@Index ANNOTATION: Flyway owns the schema (ddl-auto=validate).
-- Hibernate validates tables, columns, types and primary keys but does not validate indexes,
-- and in validate mode it does not create them either — so annotating them would have changed
-- nothing at runtime. Versioning them keeps them in the same place as the rest of the schema.

-- History reads: WHERE target_id = ? ORDER BY id DESC LIMIT n.
--
-- Column order mirrors the query exactly so Postgres answers it straight from the index with
-- no sort step. Ordered by id rather than checked_at to match CheckRepository's own choice:
-- ids are identity-incrementing, so they are strictly unique and cannot collide when several
-- probes land in the same microsecond, whereas an ordering on a timestamp is not guaranteed.
CREATE INDEX idx_target_check_target_id ON public.target_check (target_id, id DESC);

-- Retention deletes: WHERE checked_at < ?.
-- Without this the prune would be a full table scan on every run — the cleanup job scanning
-- precisely the data it exists to stop you paying for.
CREATE INDEX idx_target_check_checked_at ON public.target_check (checked_at);
