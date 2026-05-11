package com.ticketing.saga.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.saga.domain.SagaStateEntity;
import com.ticketing.saga.domain.SagaStateRepository;
import com.ticketing.saga.model.SagaState;
import com.ticketing.saga.model.SagaStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Saga state store with write-through persistence.
 *
 * Write path : Postgres first (durable), then Redis (fast cache).
 * Read path  : Redis first (fast), fall back to Postgres on miss.
 *
 * Redis is an acceleration layer — if it goes down, reads and writes
 * continue against Postgres with no data loss.
 */
@Service
public class SagaStateStore {

    private static final Logger   log        = LoggerFactory.getLogger(SagaStateStore.class);
    private static final String   KEY_PREFIX = "saga:";
    private static final Duration TTL        = Duration.ofMinutes(10);

    private static final Set<SagaStatus> TERMINAL_STATUSES = Set.of(
            SagaStatus.COMPLETED, SagaStatus.FAILED, SagaStatus.CANCELLED);

    private final SagaStateRepository            repository;
    private final RedisTemplate<String, String>  redisTemplate;
    private final ObjectMapper                   objectMapper;

    public SagaStateStore(SagaStateRepository repository,
                          RedisTemplate<String, String> redisTemplate,
                          ObjectMapper objectMapper) {
        this.repository    = repository;
        this.redisTemplate = redisTemplate;
        this.objectMapper  = objectMapper;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Persists saga state.
     * Postgres write is transactional and guaranteed.
     * Redis write is best-effort — failure is logged but does not propagate.
     */
    @Transactional
    public void save(SagaState state) {
        String json = serialize(state);
        if (json == null) return;

        // 1. Postgres — durable, transactional
        SagaStateEntity entity = repository.findById(state.getSagaId())
                .orElseGet(() -> {
                    SagaStateEntity e = new SagaStateEntity();
                    e.setSagaId(state.getSagaId());
                    return e;
                });
        entity.setStatus(state.getStatus());
        entity.setStateJson(json);
        repository.save(entity);
        log.debug("Persisted saga state sagaId={} status={}", state.getSagaId(), state.getStatus());

        // 2. Redis — cache, best-effort
        cacheWrite(state.getSagaId(), json);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Loads saga state. Reads Redis first; falls back to Postgres on a miss.
     */
    @Transactional(readOnly = true)
    public SagaState load(String sagaId) {
        // 1. Redis — fast path
        String json = cacheRead(sagaId);
        if (json != null) {
            SagaState state = deserialize(json);
            if (state != null) return state;
        }

        // 2. Postgres — reliable fallback
        SagaStateEntity entity = repository.findById(sagaId).orElse(null);
        if (entity == null) {
            log.warn("Saga state not found for sagaId={}", sagaId);
            return null;
        }

        // Repopulate Redis for subsequent reads
        cacheWrite(sagaId, entity.getStateJson());

        return deserialize(entity.getStateJson());
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Transactional
    public void delete(String sagaId) {
        repository.deleteById(sagaId);
        try {
            redisTemplate.delete(KEY_PREFIX + sagaId);
        } catch (Exception e) {
            log.warn("Redis delete failed (non-fatal) sagaId={}: {}", sagaId, e.getMessage());
        }
        log.debug("Deleted saga state sagaId={}", sagaId);
    }

    // ── Watchdog scan ─────────────────────────────────────────────────────────

    /**
     * Returns only non-terminal sagas that have been idle since before
     * {@code updatedAtThreshold} — i.e. sagas that may be stuck.
     *
     * <p>The time filter is pushed to Postgres via
     * {@code idx_saga_states_active_stale} (partial index on active rows,
     * range on {@code updated_at}). Under normal load the result set is empty
     * and the index scan terminates immediately without reading any rows.
     *
     * <p>Previously this method fetched <em>all</em> active sagas, deserialised
     * every JSON blob in Java, then filtered by {@code lastUpdatedAt} — O(N)
     * deserialisation per watchdog tick regardless of how many sagas were stuck.
     */
    @Transactional(readOnly = true)
    public List<SagaState> scanStaleSagas(Instant updatedAtThreshold) {
        return repository
                .findByStatusNotInAndUpdatedAtBefore(TERMINAL_STATUSES, updatedAtThreshold)
                .stream()
                .map(e -> deserialize(e.getStateJson()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String serialize(SagaState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            log.error("Failed to serialise saga state sagaId={}: {}", state.getSagaId(), e.getMessage(), e);
            throw new RuntimeException("Failed to serialise saga state", e);
        }
    }

    private SagaState deserialize(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, SagaState.class);
        } catch (Exception e) {
            log.error("Failed to deserialise saga state JSON: {}", e.getMessage(), e);
            return null;
        }
    }

    private void cacheWrite(String sagaId, String json) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + sagaId, json, TTL);
        } catch (Exception e) {
            log.warn("Redis write failed (non-fatal) sagaId={}: {}", sagaId, e.getMessage());
        }
    }

    private String cacheRead(String sagaId) {
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + sagaId);
        } catch (Exception e) {
            log.warn("Redis read failed (non-fatal) sagaId={}: {}", sagaId, e.getMessage());
            return null;
        }
    }
}
