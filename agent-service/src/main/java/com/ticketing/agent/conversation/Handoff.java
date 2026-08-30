package com.ticketing.agent.conversation;

import com.ticketing.agent.domain.model.AgentEvent;

import java.time.Instant;

/**
 * The agent's terminal action: an event id and a link, handed back to the
 * caller.
 *
 * <h3>What this deliberately is not</h3>
 * No hold, no reservation, no order. The agent has been read-only throughout
 * and stays read-only at the end — it names an event and stops. Everything that
 * follows is the checkout flow that already exists, with its own reservation,
 * price lock, payment and compensation.
 *
 * <p>That boundary is what makes the whole subsystem cheap. There is no TTL to
 * expire, nothing to release when a conversation is abandoned, and no way for a
 * bug here to strand a ticket. The cost of being wrong is one wasted click.
 *
 * @param eventId    the event to buy into
 * @param deepLink   where to send the person; the existing event page, which
 *                   already handles seat selection and checkout
 * @param available  whether the projection still shows this as buyable. A
 *                   courtesy check, not a guarantee — see below
 * @param reason     why not, when {@code available} is false
 */
public record Handoff(String eventId, String deepLink, boolean available, String reason) {

    /**
     * Re-checks the event against the local projection before handing it over.
     *
     * <p>Deliberately a local check rather than a call to ticket-service. The
     * projection can be seconds stale, so this cannot be authoritative and is
     * not trying to be — checkout re-validates for real, and an event that
     * sells out mid-conversation produces the same error any other buyer would
     * get. What this catches is the obviously-dead link: an event that was
     * cancelled or has already happened, where sending someone to the page
     * wastes their time for no reason.
     *
     * <p>Paying for a synchronous call to another service to close a race that
     * checkout closes anyway would buy nothing.
     */
    public static Handoff of(AgentEvent event, String baseUrl) {
        String link = baseUrl + "/events/" + event.getId();

        if (!"OPEN".equalsIgnoreCase(event.getStatus())) {
            return new Handoff(event.getId(), link, false,
                    "sự kiện không còn mở bán (" + event.getStatus() + ")");
        }
        if (event.getStartAt() != null && event.getStartAt().isBefore(Instant.now())) {
            return new Handoff(event.getId(), link, false, "sự kiện đã diễn ra");
        }
        if (event.getSalesCloseAt() != null && event.getSalesCloseAt().isBefore(Instant.now())) {
            return new Handoff(event.getId(), link, false, "đã đóng bán vé");
        }
        return new Handoff(event.getId(), link, true, null);
    }
}
