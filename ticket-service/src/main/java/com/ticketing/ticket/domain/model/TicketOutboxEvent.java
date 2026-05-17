package com.ticketing.ticket.domain.model;

import com.ticketing.common.outbox.OutboxEntry;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_events")
public class TicketOutboxEvent extends OutboxEntry {
}
