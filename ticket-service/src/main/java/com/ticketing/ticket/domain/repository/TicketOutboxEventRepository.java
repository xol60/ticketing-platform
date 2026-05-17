package com.ticketing.ticket.domain.repository;

import com.ticketing.ticket.domain.model.TicketOutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface TicketOutboxEventRepository extends JpaRepository<TicketOutboxEvent, UUID> {

    @Query("SELECT o FROM TicketOutboxEvent o WHERE o.publishedAt IS NULL ORDER BY o.createdAt ASC")
    List<TicketOutboxEvent> findUnpublishedBatch(Pageable pageable);
}
