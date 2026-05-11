package com.ticketing.saga.domain;

import com.ticketing.saga.model.SagaStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SagaStateRepository extends JpaRepository<SagaStateEntity, String> {

    /**
     * Returns active (non-terminal) sagas whose {@code updated_at} is before
     * {@code threshold} — i.e. sagas that have been idle long enough to be
     * considered stuck.
     *
     * <p>Uses {@code idx_saga_states_active_stale} (partial on status + range on
     * updated_at), so Postgres applies both filters in the index scan and returns
     * only genuinely stuck rows — typically zero rows under normal load.
     * This avoids the previous pattern of loading every active saga into Java and
     * deserialising its JSON blob just to check lastUpdatedAt.
     *
     * <p>Called by the watchdog scheduler in {@link com.ticketing.saga.service.SagaOrchestrator}.
     */
    List<SagaStateEntity> findByStatusNotInAndUpdatedAtBefore(
            Collection<SagaStatus> statuses, Instant threshold);

    /**
     * Returns all sagas whose status is NOT in the given set (no time filter).
     * Used by the admin API — returns every active saga for display, not just stuck ones.
     */
    Page<SagaStateEntity> findByStatusNotIn(Collection<SagaStatus> statuses, Pageable pageable);
}
