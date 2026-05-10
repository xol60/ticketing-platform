package com.ticketing.auth.config;

import com.ticketing.auth.domain.model.User;
import com.ticketing.auth.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the default ADMIN account on first startup if it does not exist.
 *
 * Credentials are read from environment variables so they can be overridden
 * in production without touching source code:
 *   ADMIN_EMAIL    (default: admin@ticketing.com)
 *   ADMIN_USERNAME (default: admin)
 *   ADMIN_PASSWORD (default: Admin@123456)
 *
 * The seeder is idempotent — it checks existsByEmail before inserting,
 * so restarting the service never creates duplicate accounts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder {

    private final UserRepository  userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.email:admin@ticketing.com}")
    private String adminEmail;

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Value("${admin.password:Admin@123456}")
    private String adminPassword;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (userRepository.existsByEmail(adminEmail) || userRepository.existsByUsername(adminUsername)) {
            log.info("Admin account already exists (email={} or username={}), skipping seed",
                    adminEmail, adminUsername);
            return;
        }

        User admin = User.builder()
                .email(adminEmail)
                .username(adminUsername)
                .passwordHash(passwordEncoder.encode(adminPassword))
                .role(User.Role.ADMIN)
                .enabled(true)
                .emailVerified(true)
                .build();

        userRepository.save(admin);
        log.info("Admin account created: email={} username={}", adminEmail, adminUsername);
    }
}
