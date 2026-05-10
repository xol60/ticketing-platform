package com.ticketing.notification.domain.repository;

import com.ticketing.notification.domain.model.NotificationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    List<NotificationLog> findByRecipientOrderByCreatedAtDesc(String recipient);

    /** Paginated filter by notification type — used by the admin read API. */
    Page<NotificationLog> findByType(String type, Pageable pageable);
}
