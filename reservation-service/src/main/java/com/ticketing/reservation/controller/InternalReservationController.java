package com.ticketing.reservation.controller;

import com.ticketing.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal HTTP surface consumed exclusively by other microservices inside the
 * Docker / k8s network.  These endpoints are NOT routed through Nginx / API
 * Gateway and therefore carry no authentication headers — callers are trusted
 * by network boundary.
 *
 * <p>The single endpoint answers the question: "Is this user currently allowed
 * to purchase this ticket?" without exposing any queue internals.
 */
@RestController
@RequestMapping("/internal/reservations")
@RequiredArgsConstructor
public class InternalReservationController {

    private final ReservationService reservationService;

    /**
     * Promotion access check for order-service.
     *
     * <p>Returns {@code { "allowed": true }} when:
     * <ul>
     *   <li>No exclusive purchase window is active for the ticket (no queue contention).</li>
     *   <li>The requesting user holds the exclusive purchase window (PROMOTED status).</li>
     * </ul>
     *
     * <p>Returns {@code { "allowed": false }} when another user currently holds
     * the exclusive window — meaning the requesting user is bypassing the queue.
     *
     * @param ticketId the ticket the caller intends to purchase
     * @param userId   the user making the purchase attempt
     */
    @GetMapping("/{ticketId}/promotion-check")
    public Map<String, Object> checkPromotionAccess(
            @PathVariable String ticketId,
            @RequestParam String userId) {
        boolean allowed = reservationService.checkPromotionAccess(ticketId, userId);
        return Map.of(
                "allowed",  allowed,
                "ticketId", ticketId,
                "userId",   userId
        );
    }
}
