package com.ticketing.saga.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.saga.model.SagaState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class SagaStateStore {

    private static final Logger log = LoggerFactory.getLogger(SagaStateStore.class);
    private static final String KEY_PREFIX = "saga:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public SagaStateStore(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    private String toKey(String sagaId) {
        return KEY_PREFIX + sagaId;
    }

    public void save(SagaState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(toKey(state.getSagaId()), json, TTL);
            log.debug("Saved saga state: sagaId={}, status={}", state.getSagaId(), state.getStatus());
        } catch (Exception e) {
            log.error("Failed to save saga state for sagaId={}: {}", state.getSagaId(), e.getMessage(), e);
            throw new RuntimeException("Failed to save saga state", e);
        }
    }

    public SagaState load(String sagaId) {
        try {
            String json = redisTemplate.opsForValue().get(toKey(sagaId));
            if (json == null) {
                log.warn("Saga state not found for sagaId={}", sagaId);
                return null;
            }
            return objectMapper.readValue(json, SagaState.class);
        } catch (Exception e) {
            log.error("Failed to load saga state for sagaId={}: {}", sagaId, e.getMessage(), e);
            throw new RuntimeException("Failed to load saga state", e);
        }
    }

    public void delete(String sagaId) {
        redisTemplate.delete(toKey(sagaId));
        log.debug("Deleted saga state: sagaId={}", sagaId);
    }

    /**
     * Scans Redis for all saga:* keys and returns the corresponding SagaState objects.
     * Used by the watchdog to detect stuck sagas.
     */
    public List<SagaState> scanAllSagas() {
        List<SagaState> results = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                try {
                    String json = redisTemplate.opsForValue().get(key);
                    if (json != null) {
                        SagaState state = objectMapper.readValue(json, SagaState.class);
                        results.add(state);
                    }
                } catch (Exception e) {
                    log.warn("Failed to deserialize saga state for key={}: {}", key, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Error scanning saga keys: {}", e.getMessage(), e);
        }
        return results;
    }
}
