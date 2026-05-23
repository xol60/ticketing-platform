package com.ticketing.search.kafka;

import com.ticketing.common.events.EventSearchIndexedEvent;
import com.ticketing.common.events.EventStatus;
import com.ticketing.search.domain.model.EventDocument;
import com.ticketing.search.domain.repository.EventSearchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Behavioural contract for {@link EventIndexConsumer}.
 *
 * <p>Three concerns under test:
 * <ol>
 *   <li><b>Status routing</b> — OPEN → {@code save(...)}, anything else →
 *       {@code deleteById(...)}. This is the single most important invariant
 *       of the indexing pipeline; if it breaks, cancelled events stay
 *       searchable.</li>
 *   <li><b>Idempotent payload mapping</b> — every field on the inbound event
 *       is copied verbatim into the {@code EventDocument} on save. We verify
 *       via {@link ArgumentCaptor} so a future refactor that drops a field
 *       (e.g. {@code genre}) is caught immediately.</li>
 *   <li><b>Ack semantics</b> — ack ONLY on success. If ES throws, the
 *       exception must propagate (so Spring-Kafka redelivers) and the
 *       acknowledgment must NOT have been called.</li>
 * </ol>
 *
 * <p>Malformed payload (null eventId) is acked-and-skipped — silently
 * dropping a poison message is preferable to a redelivery loop that never
 * resolves.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventIndexConsumer — Kafka → ES indexing contract")
class EventIndexConsumerTest {

    @Mock EventSearchRepository repository;
    @Mock Acknowledgment        ack;

    @InjectMocks EventIndexConsumer consumer;

    @Test
    @DisplayName("OPEN event → repository.save() and the document carries every field")
    void openEvent_upsertsDocumentWithAllFields() {
        Instant eventDate    = Instant.parse("2026-08-15T20:00:00Z");
        Instant salesOpen    = Instant.parse("2026-06-01T10:00:00Z");
        Instant salesClose   = Instant.parse("2026-08-14T23:59:59Z");

        EventSearchIndexedEvent in = new EventSearchIndexedEvent(
                "trace-1", "saga-1",
                "evt-42", "Coldplay World Tour", EventStatus.OPEN.name(),
                salesOpen, salesClose, eventDate,
                "Coldplay", "Madison Square Garden", "New York",
                "Music of the Spheres", "Long-form description of the show...",
                "CONCERT", "POP");

        consumer.onEvent(in, ack);

        ArgumentCaptor<EventDocument> captor = ArgumentCaptor.forClass(EventDocument.class);
        verify(repository).save(captor.capture());
        verify(repository, never()).deleteById(any());
        verify(ack).acknowledge();

        EventDocument saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("evt-42");
        assertThat(saved.getName()).isEqualTo("Coldplay World Tour");
        assertThat(saved.getPrimaryArtist()).isEqualTo("Coldplay");
        assertThat(saved.getVenueName()).isEqualTo("Madison Square Garden");
        assertThat(saved.getVenueCity()).isEqualTo("New York");
        assertThat(saved.getShortDescription()).isEqualTo("Music of the Spheres");
        assertThat(saved.getFullDescription()).isEqualTo("Long-form description of the show...");
        assertThat(saved.getCategory()).isEqualTo("CONCERT");
        assertThat(saved.getGenre()).isEqualTo("POP");
        assertThat(saved.getStatus()).isEqualTo("OPEN");
        assertThat(saved.getEventDate()).isEqualTo(eventDate);
        assertThat(saved.getSalesOpenAt()).isEqualTo(salesOpen);
        assertThat(saved.getSalesCloseAt()).isEqualTo(salesClose);
    }

    @Test
    @DisplayName("CANCELLED event → repository.deleteById() (must disappear from search)")
    void cancelledEvent_deletesFromIndex() {
        EventSearchIndexedEvent in = openEvent("evt-99");
        in.setStatus(EventStatus.CANCELLED.name());

        consumer.onEvent(in, ack);

        verify(repository).deleteById("evt-99");
        verify(repository, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("SALES_CLOSED event → deleteById (same delete branch as CANCELLED)")
    void salesClosedEvent_deletesFromIndex() {
        EventSearchIndexedEvent in = openEvent("evt-100");
        in.setStatus(EventStatus.SALES_CLOSED.name());

        consumer.onEvent(in, ack);

        verify(repository).deleteById("evt-100");
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("COMPLETED event → deleteById")
    void completedEvent_deletesFromIndex() {
        EventSearchIndexedEvent in = openEvent("evt-101");
        in.setStatus(EventStatus.COMPLETED.name());

        consumer.onEvent(in, ack);

        verify(repository).deleteById("evt-101");
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("DRAFT event → deleteById (never publicly searchable)")
    void draftEvent_deletesFromIndex() {
        EventSearchIndexedEvent in = openEvent("evt-102");
        in.setStatus(EventStatus.DRAFT.name());

        consumer.onEvent(in, ack);

        verify(repository).deleteById("evt-102");
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("ES failure → exception propagates AND ack is NOT called (Kafka redelivers)")
    void esFailure_doesNotAck() {
        EventSearchIndexedEvent in = openEvent("evt-50");
        doThrow(new RuntimeException("ES down")).when(repository).save(any());

        // The listener rethrows so Spring-Kafka's default error handler can back off + retry.
        try {
            consumer.onEvent(in, ack);
            org.junit.jupiter.api.Assertions.fail("Expected RuntimeException to propagate");
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage()).isEqualTo("ES down");
        }

        verify(repository).save(any());
        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("Malformed payload (null eventId) → acked and skipped, no repository call")
    void malformedPayload_ackedAndSkipped() {
        EventSearchIndexedEvent in = new EventSearchIndexedEvent();
        // eventId is null

        consumer.onEvent(in, ack);

        verifyNoInteractions(repository);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Status string comparison is case-insensitive (defensive against producer drift)")
    void statusComparison_isCaseInsensitive() {
        EventSearchIndexedEvent in = openEvent("evt-77");
        in.setStatus("open");  // lowercase variant

        consumer.onEvent(in, ack);

        verify(repository).save(any());
        verify(repository, never()).deleteById(any());
    }

    /** Helper — minimal OPEN event with valid required fields. */
    private static EventSearchIndexedEvent openEvent(String id) {
        return new EventSearchIndexedEvent(
                "trace", "saga",
                id, "Test Event", EventStatus.OPEN.name(),
                Instant.now(), Instant.now().plusSeconds(86400), Instant.now().plusSeconds(172800),
                "Test Artist", "Test Venue", "Test City",
                "short", "full",
                "CONCERT", "ROCK");
    }
}
