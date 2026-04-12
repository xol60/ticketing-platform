package com.ticketing.reservation.domain.repository;

import com.ticketing.reservation.domain.model.Reservation;
import com.ticketing.reservation.domain.model.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByTicketIdAndStatus(String ticketId, ReservationStatus status);

    Optional<Reservation> findByUserIdAndTicketIdAndStatus(
            String userId, String ticketId, ReservationStatus status);

    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant threshold);
}
