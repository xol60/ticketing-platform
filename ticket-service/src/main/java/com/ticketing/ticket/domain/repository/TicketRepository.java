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
}
