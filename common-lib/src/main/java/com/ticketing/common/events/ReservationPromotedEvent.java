package com.ticketing.common.events;

import lombok.*;

@Getter @Setter @NoArgsConstructor
public class ReservationPromotedEvent extends DomainEvent {
    private String ticketId;
    private String userId;
    private String reservationId;

    public ReservationPromotedEvent(String traceId, String sagaId,
                                    String ticketId, String userId, String reservationId) {
        super(traceId, sagaId);
        this.ticketId      = ticketId;
        this.userId        = userId;
        this.reservationId = reservationId;
    }
}
