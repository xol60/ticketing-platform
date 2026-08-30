package com.ticketing.agent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Conversation state in Redis, keyed by session.
 *
 * <h3>Why a TTL is right here and wrong elsewhere</h3>
 * Saga state in this platform is deliberately durable, because losing it
 * strands a payment. Nothing of the sort is true here: the worst outcome of an
 * expired conversation is that the person types their request again. Forty-five
 * minutes is well past the point where anyone resumes a search they walked away
 * from, and holding it longer would keep dead sessions in memory for no one.
 *
 * <h3>Failing open</h3>
 * Every method here swallows Redis failures and behaves as though the session
 * were new. A search still works without memory — it is a worse experience, not
 * a broken one — and taking the endpoint down because a cache is unavailable
 * would turn a degradation into an outage.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationStore {

    private static final String KEY_PREFIX = "conv:";
    private static final Duration TTL = Duration.ofMinutes(45);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public Optional<ConversationState> load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + sessionId);
            return json == null ? Optional.empty()
                                : Optional.of(mapper.readValue(json, ConversationState.class));
        } catch (Exception e) {
            log.warn("Could not load conversation {} — treating as new: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Writes and refreshes the TTL, so an active conversation never expires mid-use. */
    public void save(ConversationState state) {
        if (state == null || state.getSessionId() == null) return;
        try {
            redis.opsForValue().set(KEY_PREFIX + state.getSessionId(),
                    mapper.writeValueAsString(state), TTL);
        } catch (Exception e) {
            log.warn("Could not save conversation {}: {}", state.getSessionId(), e.getMessage());
        }
    }
}
