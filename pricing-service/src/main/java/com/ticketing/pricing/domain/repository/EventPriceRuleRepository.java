package com.ticketing.pricing.domain.repository;

import com.ticketing.pricing.domain.model.EventPriceRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventPriceRuleRepository extends JpaRepository<EventPriceRule, UUID> {

    Optional<EventPriceRule> findByEventId(String eventId);
}
