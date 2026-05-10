package com.ticketing.reservation.controller;

import com.ticketing.common.dto.ApiResponse;
import com.ticketing.reservation.domain.model.Reservation;
import com.ticketing.reservation.domain.model.ReservationStatus;
import com.ticketing.reservation.domain.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/reservations")
@RequiredArgsConstructor
public class AdminReservationController {

    private final ReservationRepository reservationRepository;

    @GetMapping
    public ApiResponse<Page<Reservation>> listReservations(
            @RequestParam(defaultValue = "0")    int page,
            @RequestParam(defaultValue = "20")   int size,
            @RequestParam(required = false)      ReservationStatus status,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        PageRequest pr = PageRequest.of(page, size, Sort.by("queuedAt").descending());
        Page<Reservation> result = (status != null)
                ? reservationRepository.findByStatus(status, pr)
                : reservationRepository.findAll(pr);
        return ApiResponse.ok(result, traceId);
    }
}
