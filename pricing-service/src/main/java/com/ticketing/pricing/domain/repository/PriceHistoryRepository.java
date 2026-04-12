package com.ticketing.pricing.domain.repository;

import com.ticketing.pricing.domain.model.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, UUID> {

    /**
     * Close the currently active (valid_to IS NULL) record for an event.
     */
    @Modifying
    @Query("""
           UPDATE PriceHistory ph SET ph.validTo = :now
           WHERE ph.eventId = :eventId AND ph.validTo IS NULL
           """)
    int closeActive(@Param("eventId") String eventId, @Param("now") Instant now);

    /**
     * Point-in-time lookup: price that was active at the given instant.
     */
    @Query("""
           SELECT ph.price FROM PriceHistory ph
           WHERE ph.eventId    = :eventId
             AND ph.validFrom <= :at
             AND (ph.validTo IS NULL OR ph.validTo > :at)
           ORDER BY ph.validFrom DESC
           """)
    BigDecimal findPriceAt(@Param("eventId") String eventId, @Param("at") Instant at);

    /**
     * Check if a price value ever existed in history within the given window.
     * Used to distinguish fabricated prices (never existed) from stale prices (existed but changed).
     */
    @Query("""
           SELECT COUNT(ph) > 0 FROM PriceHistory ph
           WHERE ph.eventId = :eventId
             AND ph.price   = :price
             AND ph.validFrom >= :since
           """)
    boolean existsInRecentHistory(
            @Param("eventId") String eventId,
            @Param("price")   BigDecimal price,
            @Param("since")   Instant since);
}
