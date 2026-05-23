package com.ticketing.ticket.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.events.TicketReleasedEvent;
import com.ticketing.common.events.TicketReservedEvent;
import com.ticketing.common.events.TicketReserveCommand;
import com.ticketing.common.exception.ErrorCode;
import com.ticketing.ticket.domain.model.Event;
import com.ticketing.common.events.EventStatus;
import com.ticketing.ticket.domain.model.Ticket;
import com.ticketing.ticket.domain.model.TicketStatus;
import com.ticketing.ticket.client.PricingClient;
import com.ticketing.ticket.domain.repository.EventRepository;
import com.ticketing.ticket.domain.repository.TicketRepository;
import com.ticketing.ticket.kafka.TicketEventPublisher;
import com.ticketing.ticket.mapper.TicketMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the Q4 idempotency fix in {@link TicketService#handleReserveCommand}.
 *
 * <p>Before the fix, Kafka redelivery of a TicketReserveCommand for a ticket already
 * reserved by the same order would publish {@code TicketReleasedEvent(TICKET_UNAVAILABLE)},
 * causing the saga to cancel a valid order.
 *
 * <p>After the fix, the handler detects "same status, same owner" and republishes
 * {@code TicketReservedEvent} — saga sees the duplicate event for a state it has already
 * advanced past and harmlessly ignores it. No spurious cancellation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TicketService — reserve idempotency under Kafka redelivery")
class TicketServiceIdempotencyTest {

    @Mock TicketRepository      ticketRepository;
    @Mock TicketMapper          ticketMapper;
    @Mock TicketEventPublisher  eventPublisher;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock EventRepository       eventRepository;
    @Mock ObjectMapper          objectMapper;
    @Mock TransactionTemplate   txTemplate;
    @Mock PricingClient         pricingClient;   // Not exercised by reserve-idempotency tests, but
                                                  // required by the constructor since Phase 3b
                                                  // wired the public ticket-list endpoint to it.

    private TicketService ticketService;

    private static final String TICKET_ID = "ticket-001";
    private static final String EVENT_ID  = "event-001";
    private static final String ORDER_A   = "order-A";
    private static final String ORDER_B   = "order-B";
    private static final String USER_ID   = "user-001";
    private static final String SAGA_ID   = "saga-001";
    private static final String TRACE_ID  = "trace-001";
    private static final BigDecimal FACE_PRICE = new BigDecimal("100.00");

    @BeforeEach
    void setUp() {
        ticketService = new TicketService(
                ticketRepository, ticketMapper, eventPublisher,
                redisTemplate, eventRepository, objectMapper, txTemplate,
                pricingClient);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Redis SETNX always succeeds in these tests — we're testing the DB-level idempotency
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        // Make txTemplate.execute(callback) actually execute the callback inline
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });

        // Open event by default — sales window must include "now" for isOpenForSales()
        Event openEvent = Event.builder()
                .id(EVENT_ID)
                .name("Test Event")
                .status(EventStatus.OPEN)
                .salesOpenAt(Instant.now().minus(Duration.ofHours(1)))
                .salesCloseAt(Instant.now().plus(Duration.ofHours(1)))
                .eventDate(Instant.now().plus(Duration.ofDays(7)))
                .build();
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(openEvent));
    }

    private Ticket availableTicket() {
        return Ticket.builder()
                .id(TICKET_ID)
                .eventId(EVENT_ID)
                .facePrice(FACE_PRICE)
                .status(TicketStatus.AVAILABLE)
                .version(0L)
                .build();
    }

    private Ticket reservedByOrder(String orderId) {
        Ticket t = availableTicket();
        t.reserve(orderId, USER_ID, FACE_PRICE, Instant.now().plus(Duration.ofMinutes(2)));
        return t;
    }

    private TicketReserveCommand command(String orderId) {
        return new TicketReserveCommand(TRACE_ID, SAGA_ID, TICKET_ID, orderId, USER_ID);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path — establishes baseline before the idempotency cases
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("first delivery")
    class FirstDelivery {

        @Test
        @DisplayName("AVAILABLE ticket → reserves and publishes TicketReservedEvent")
        void available_ticket_reserves_and_publishes_reserved() {
            when(ticketRepository.findById(TICKET_ID))
                    .thenReturn(Optional.of(availableTicket()));
            when(ticketRepository.saveAndFlush(any(Ticket.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ticketService.handleReserveCommand(command(ORDER_A));

            verify(eventPublisher).publishReserved(any(TicketReservedEvent.class));
            verify(eventPublisher, never()).publishReleased(any(TicketReleasedEvent.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // THE IDEMPOTENCY FIX — Q4
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Kafka redelivery — SAME order")
    class DuplicateDeliverySameOrder {

        @Test
        @DisplayName("RESERVED-by-same-order → republishes TicketReservedEvent (idempotent)")
        void duplicate_reserve_from_same_order_republishes_reserved() {
            // Ticket is already RESERVED by ORDER_A from the first delivery
            when(ticketRepository.findById(TICKET_ID))
                    .thenReturn(Optional.of(reservedByOrder(ORDER_A)));

            // Kafka redelivers the SAME command (same orderId)
            ticketService.handleReserveCommand(command(ORDER_A));

            // ── THE CRITICAL ASSERTION ──
            // Must publish RESERVED (not RELEASED) so saga sees the duplicate event
            // for a state it has already advanced past. Saga harmlessly ignores it.
            ArgumentCaptor<TicketReservedEvent> evt =
                    ArgumentCaptor.forClass(TicketReservedEvent.class);
            verify(eventPublisher).publishReserved(evt.capture());
            assertThat(evt.getValue().getOrderId()).isEqualTo(ORDER_A);
            assertThat(evt.getValue().getTicketId()).isEqualTo(TICKET_ID);

            // Must NOT publish a release event — this would falsely cancel the saga
            verify(eventPublisher, never()).publishReleased(any(TicketReleasedEvent.class));

            // Must NOT write the row again — idempotent retries skip the UPDATE
            verify(ticketRepository, never()).saveAndFlush(any(Ticket.class));
        }
    }

    @Nested
    @DisplayName("Concurrent reserve — DIFFERENT order")
    class ConcurrentReserveDifferentOrder {

        @Test
        @DisplayName("RESERVED-by-other-order → publishes TicketReleased(TICKET_UNAVAILABLE)")
        void reserve_when_held_by_other_order_publishes_unavailable() {
            // Ticket is reserved by ORDER_A (a different order)
            when(ticketRepository.findById(TICKET_ID))
                    .thenReturn(Optional.of(reservedByOrder(ORDER_A)));

            // ORDER_B tries to reserve the same ticket
            ticketService.handleReserveCommand(command(ORDER_B));

            // Must publish RELEASED so order B's saga compensates correctly
            ArgumentCaptor<TicketReleasedEvent> evt =
                    ArgumentCaptor.forClass(TicketReleasedEvent.class);
            verify(eventPublisher).publishReleased(evt.capture());
            assertThat(evt.getValue().getOrderId()).isEqualTo(ORDER_B);
            assertThat(evt.getValue().getReason())
                    .isEqualTo(ErrorCode.TICKET_UNAVAILABLE.name());

            // Must NOT publish RESERVED — that would falsely tell saga B it won
            verify(eventPublisher, never()).publishReserved(any(TicketReservedEvent.class));
        }
    }
}
