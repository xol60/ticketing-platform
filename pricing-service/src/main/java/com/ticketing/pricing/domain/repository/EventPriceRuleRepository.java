package com.ticketing.pricing.domain.repository;

import com.ticketing.pricing.domain.model.EventPriceRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventPriceRuleRepository extends JpaRepository<EventPriceRule, UUID> {

    Optional<EventPriceRule> findByEventId(String eventId);

    @Modifying
    @Query("UPDATE EventPriceRule r SET r.soldTickets = r.soldTickets + 1 WHERE r.eventId = :eventId")
    void incrementSoldTickets(@Param("eventId") String eventId);

    @Modifying
    @Query("UPDATE EventPriceRule r SET r.soldTickets = GREATEST(r.soldTickets - 1, 0) WHERE r.eventId = :eventId")
    void decrementSoldTickets(@Param("eventId") String eventId);
}
