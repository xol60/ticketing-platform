package com.ticketing.ticket.domain.repository;

import com.ticketing.ticket.domain.model.Ticket;
import com.ticketing.ticket.domain.model.TicketStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, String> {

    List<Ticket> findByEventId(String eventId);

    List<Ticket> findByEventIdAndStatus(String eventId, TicketStatus status);

    // NOTE: We deliberately do NOT expose a `findByIdForUpdate` (SELECT ... FOR UPDATE).
    // The reservation, confirm, and release paths all use Redis SETNX + JPA @Version
    // optimistic locking — both non-blocking. Adding a pessimistic-lock helper here would
    // tempt future code to introduce thread-blocking on the small Kafka consumer pool.

    boolean existsByEventIdAndSectionAndRowAndSeat(
            String eventId, String section, String row, String seat);

    long countByEventIdAndStatus(String eventId, TicketStatus status);

    /**
     * Price range and seat count for one event, in a single scan.
     *
     * <p>Published on {@code event.search.indexed} so downstream read models
     * get facts they cannot compute for themselves — only this service can see
     * the ticket table. search-service uses the prices to filter and sort;
     * agent-service turns the count into a capacity band and rejects any
     * extracted facet that contradicts it.
     *
     * <p>Counts every ticket regardless of status, because this describes the
     * venue's size, not what is still on sale. A sold-out stadium is still a
     * stadium.
     *
     * <p>Returns a row of nulls and zero for an event with no tickets yet.
     */
    @Query("""
            SELECT MIN(t.facePrice) AS minPrice,
                   MAX(t.facePrice) AS maxPrice,
                   COUNT(t)         AS ticketCount
              FROM Ticket t
             WHERE t.eventId = :eventId
            """)
    EventTicketSummary summariseTickets(@Param("eventId") String eventId);

    /** Projection for {@link #summariseTickets(String)}. */
    interface EventTicketSummary {
        BigDecimal getMinPrice();
        BigDecimal getMaxPrice();
        long getTicketCount();
    }

    /**
     * Used by the stuck-reservation watchdog.
     * Returns every RESERVED ticket whose explicit {@code reservedUntil} deadline
     * has already passed — meaning the saga that locked it has exceeded its maximum
     * allowed runtime and can safely be considered crashed or timed out.
     *
     * <p>Deadline-based (not age-based) so the watchdog never fires while a payment
     * is still legitimately in flight: the saga controls the deadline when it reserves.
     */
    List<Ticket> findByStatusAndReservedUntilBefore(TicketStatus status, java.time.Instant now);

    // ── Batch-insert duplicate detection — projection (row + seat only) ───────

    /**
     * Lightweight projection used by the batch-insert duplicate check.
     * Fetches only the two fields needed — avoids hydrating full Ticket entities.
     */
    interface SeatKey {
        String getRow();
        String getSeat();
    }

    /** Existing seats in the event where section IS NULL. */
    @Query("SELECT t.row AS row, t.seat AS seat FROM Ticket t " +
           "WHERE t.eventId = :eventId AND t.section IS NULL")
    List<SeatKey> findSeatKeysByEventIdAndSectionNull(@Param("eventId") String eventId);

    /** Existing seats in the event for a specific section. */
    @Query("SELECT t.row AS row, t.seat AS seat FROM Ticket t " +
           "WHERE t.eventId = :eventId AND t.section = :section")
    List<SeatKey> findSeatKeysByEventIdAndSection(
            @Param("eventId") String eventId,
            @Param("section") String section);

    // ── Public ticket-list projection (event detail page) ─────────────────────
    //
    // Used by the "list available tickets for an event" public endpoint.
    // The projection avoids hydrating full Ticket entities for every row on
    // every page render — the entity has ~17 columns, this projection has 5.
    //
    // Hits {@code idx_tickets_event_status (event_id, status)} for an
    // index-only scan; sub-5ms for thousands of available tickets.

    interface PublicTicketRow {
        String     getId();
        String     getSection();
        String     getRow();
        String     getSeat();
        BigDecimal getFacePrice();
    }

    @Query("SELECT t.id AS id, t.section AS section, t.row AS row, " +
           "       t.seat AS seat, t.facePrice AS facePrice " +
           "  FROM Ticket t " +
           " WHERE t.eventId = :eventId AND t.status = :status " +
           " ORDER BY t.section, t.row, t.seat")
    List<PublicTicketRow> findPublicByEventIdAndStatus(
            @Param("eventId") String eventId,
            @Param("status")  TicketStatus status,
            Pageable pageable);
}
