package com.ticketing.ticket.domain.repository;

import com.ticketing.ticket.domain.model.Event;
import com.ticketing.ticket.domain.model.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<Event, String> {
    List<Event> findByStatus(EventStatus status);
}
