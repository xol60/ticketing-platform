package com.ticketing.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequest {

    /**
     * Identifies the conversation.
     *
     * <p>Supplied by the caller, not derived from a login — the funnel has to
     * work for someone who has not signed in, and tying memory to an account
     * would mean the first message either forces a login or is forgotten.
     */
    @NotBlank
    @Size(max = 64)
    private String sessionId;

    @NotBlank
    @Size(max = 500)
    private String message;

    /** Assumed when no city has been mentioned in the conversation so far. */
    private String city;
}
