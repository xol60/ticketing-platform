package com.ticketing.auth.domain.repository;

import com.ticketing.auth.domain.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    @Transactional(readOnly = true)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Transactional(readOnly = true)
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.user.id = :userId " +
           "AND rt.revoked = false AND rt.expiresAt > :now " +
           "ORDER BY rt.createdAt DESC")
    List<RefreshToken> findActiveByUserId(String userId, Instant now);

    @Transactional(readOnly = true)
    long countByUserIdAndRevokedFalse(String userId);

    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken rt SET rt.revoked = true, rt.revokedAt = :now " +
           "WHERE rt.user.id = :userId AND rt.revoked = false")
    int revokeAllForUser(String userId, Instant now);

    @Modifying
    @Transactional
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :cutoff")
    int deleteExpiredTokens(Instant cutoff);

    /** Evicts oldest tokens when user exceeds max allowed active tokens. */
    @Transactional(readOnly = true)
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.user.id = :userId " +
           "AND rt.revoked = false ORDER BY rt.createdAt ASC")
    List<RefreshToken> findByUserIdOrderByCreatedAtAsc(String userId);
}
