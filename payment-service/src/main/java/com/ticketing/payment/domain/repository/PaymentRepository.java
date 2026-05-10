package com.ticketing.payment.domain.repository;

import com.ticketing.payment.domain.model.Payment;
import com.ticketing.payment.domain.model.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByOrderId(String orderId);

    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);
}
