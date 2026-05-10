package com.ticketing.saga.domain;

import com.ticketing.saga.model.SagaStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SagaStateRepository extends JpaRepository<SagaStateEntity, String> {

    /**
     * Returns all sagas whose status is NOT in the given set.
     * Used by the watchdog to fetch only active (non-terminal) sagas.
     */
    List<SagaStateEntity> findByStatusNotIn(Collection<SagaStatus> statuses);

    /** Paginated variant used by the admin read API. */
    Page<SagaStateEntity> findByStatusNotIn(Collection<SagaStatus> statuses, Pageable pageable);
}
