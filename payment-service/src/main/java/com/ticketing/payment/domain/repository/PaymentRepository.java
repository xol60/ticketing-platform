package com.ticketing.payment.domain.repository;

import com.ticketing.payment.domain.model.Payment;
import com.ticketing.payment.domain.model.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByOrderId(String orderId);

    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);

    /**
     * Returns PENDING payments whose {@code nextRetryAt} is at or before {@code now}.
     *
     * <p>Used by {@link com.ticketing.payment.watchdog.PaymentRetryWatchdog} every 2 s.
     * Hits {@code idx_payments_retry_due} (partial index on PENDING rows) — typically
     * returns 0 rows under normal load; the scan is essentially free.
     *
     * <p>Limits to 50 rows per tick to bound watchdog processing time and avoid
     * overwhelming the gateway under burst load.
     */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.status = 'PENDING'
              AND p.nextRetryAt <= :now
            ORDER BY p.nextRetryAt ASC
            """)
    List<Payment> findDueForRetry(@Param("now") Instant now,
                                  org.springframework.data.domain.Pageable pageable);
}
