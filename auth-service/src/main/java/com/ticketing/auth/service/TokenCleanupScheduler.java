package com.ticketing.auth.service;

import com.ticketing.auth.domain.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenCleanupScheduler {

    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Runs at 2:00 AM every day.
     * Deletes refresh tokens that expired more than 7 days ago to keep the table lean.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        Instant cutoff = Instant.now().minusSeconds(7 * 24 * 3600);
        int deleted = refreshTokenRepository.deleteExpiredTokens(cutoff);
        log.info("Purged {} expired refresh tokens older than {}", deleted, cutoff);
    }
}
