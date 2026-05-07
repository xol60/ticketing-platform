package com.ticketing.common.events;

import lombok.*;

/**
 * Sent by the saga orchestrator to the payment service whenever a saga that
 * already initiated a charge needs to be unwound.
 *
 * <p>Two cases:
 * <ol>
 *   <li><b>Payment in flight</b> ({@code paymentReference == null}): the
 *       {@code PaymentChargeCommand} was sent but no {@code PaymentSucceededEvent}
 *       arrived yet. The payment service marks the record {@code CANCELLATION_REQUESTED};
 *       when the gateway eventually responds with a success, the service immediately
 *       calls {@code gateway.refund()} rather than publishing {@code PaymentSucceededEvent}.
 *   </li>
 *   <li><b>Payment already charged</b> ({@code paymentReference != null}): the gateway
 *       already responded with a success, but the saga failed afterwards (e.g. ticket
 *       confirm failed, watchdog fired). The payment service immediately calls
 *       {@code gateway.refund(reference)} and publishes {@code PaymentRefundedEvent}.
 *   </li>
 * </ol>
 */
@Getter @Setter @NoArgsConstructor
public class PaymentCancelCommand extends DomainEvent {

    private String orderId;

    /**
     * Non-null when payment has already succeeded and an immediate refund is needed.
     * Null when payment is still in flight (charge not yet completed).
     */
    private String paymentReference;

    public PaymentCancelCommand(String traceId, String sagaId,
                                String orderId, String paymentReference) {
        super(traceId, sagaId);
        this.orderId          = orderId;
        this.paymentReference = paymentReference;
    }
}
