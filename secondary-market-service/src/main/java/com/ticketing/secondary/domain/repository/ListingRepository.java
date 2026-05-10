package com.ticketing.secondary.domain.repository;

import com.ticketing.secondary.domain.model.Listing;
import com.ticketing.secondary.domain.model.ListingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ListingRepository extends JpaRepository<Listing, String> {

    List<Listing> findByEventIdAndStatus(String eventId, ListingStatus status);

    List<Listing> findBySellerIdAndStatus(String sellerId, ListingStatus status);

    /** Fast duplicate check before inserting — backstopped by the partial unique index. */
    boolean existsByTicketIdAndStatus(String ticketId, ListingStatus status);

    /** Paginated filter by status — used by the admin read API. */
    Page<Listing> findByStatus(ListingStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Listing l WHERE l.id = :id")
    Optional<Listing> findByIdForUpdate(@Param("id") String id);
}
