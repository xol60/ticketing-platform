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
     * Point-in-time lookup: surge multiplier that was active at the given instant.
     */
    @Query("""
           SELECT ph.surgeMultiplier FROM PriceHistory ph
           WHERE ph.eventId    = :eventId
             AND ph.validFrom <= :at
             AND (ph.validTo IS NULL OR ph.validTo > :at)
           ORDER BY ph.validFrom DESC
           """)
    BigDecimal findMultiplierAt(@Param("eventId") String eventId, @Param("at") Instant at);

    /**
     * Check if a multiplier value was active in the recent past — i.e. still active
     * now, or closed less than {@code HISTORY_VALIDITY_WINDOW} ago.
     *
     * <p>Used by {@code PricingService.lockPrice()} to distinguish fabricated prices
     * (an attacker making up a multiplier) from stale prices (a legitimate user whose
     * UI quote is outdated because the surge moved between page load and Buy click).
     *
     * <p>The window anchors on {@code validTo} — when the multiplier <em>closed</em> —
     * not on {@code validFrom}. A long-running multiplier that closed 5 minutes ago is
     * "recent" even if it opened 3 hours earlier. The earlier {@code validFrom}-based
     * check rejected such users' orders as "fabricated" — they paid a real recent
     * price but the row's open timestamp was older than the window.
     *
     * @param eventId    the event whose history we're checking
     * @param multiplier the multiplier the user's price implies (userPrice / facePrice)
     * @param since      cutoff for "recent" — typically {@code now - HISTORY_VALIDITY_WINDOW}
     * @return true if any history row for the given multiplier is still active
     *         ({@code validTo IS NULL}) or closed at/after {@code since}
     */
    @Query("""
           SELECT COUNT(ph) > 0 FROM PriceHistory ph
           WHERE ph.eventId        = :eventId
             AND ph.surgeMultiplier = :multiplier
             AND (ph.validTo IS NULL OR ph.validTo >= :since)
           """)
    boolean existsInRecentHistory(
            @Param("eventId")    String eventId,
            @Param("multiplier") BigDecimal multiplier,
            @Param("since")      Instant since);
}
