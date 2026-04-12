package com.ticketing.auth.domain.repository;

import com.ticketing.auth.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    // All read queries annotated @Transactional(readOnly=true) →
    // DataSourceConfig.RoutingDataSource routes them to the slave.

    @Transactional(readOnly = true)
    Optional<User> findByEmail(String email);

    @Transactional(readOnly = true)
    Optional<User> findByUsername(String username);

    @Transactional(readOnly = true)
    boolean existsByEmail(String email);

    @Transactional(readOnly = true)
    boolean existsByUsername(String username);

    @Transactional(readOnly = true)
    @Query("SELECT u FROM User u WHERE u.id = :id AND u.enabled = true")
    Optional<User> findActiveById(String id);
}
