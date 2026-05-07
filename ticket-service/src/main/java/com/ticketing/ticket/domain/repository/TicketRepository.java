package com.ticketing.ticket.domain.repository;

import com.ticketing.ticket.domain.model.Ticket;
import com.ticketing.ticket.domain.model.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, String> {

    List<Ticket> findByEventId(String eventId);

    List<Ticket> findByEventIdAndStatus(String eventId, TicketStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") String id);

    boolean existsByEventIdAndSectionAndRowAndSeat(
            String eventId, String section, String row, String seat);

    long countByEventIdAndStatus(String eventId, TicketStatus status);

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
}
