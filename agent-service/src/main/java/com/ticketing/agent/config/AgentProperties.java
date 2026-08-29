package com.ticketing.agent.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Everything tunable about the agent, bound from the {@code agent.*} block.
 *
 * <p>Validated at startup so a missing model name or a bad threshold fails the
 * container rather than surfacing as strange retrieval behaviour hours later.
 * API keys are deliberately <em>not</em> {@code @NotBlank}: the service must
 * still boot without them so the schema migrates and the review UI works in an
 * environment that has no LLM credentials. The clients themselves refuse to
 * call out with an empty key, which is a clearer failure than a 401 from a
 * vendor.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    @Valid private Llm       llm       = new Llm();
    @Valid private Embedding embedding = new Embedding();
    @Valid private Ingestion ingestion = new Ingestion();

    @Data
    public static class Llm {
        @NotBlank private String baseUrl;
        private String apiKey = "";
        @NotBlank private String model;
        @Positive private int maxTokens = 2048;
        @Positive private int connectTimeoutMs = 2000;
        @Positive private int readTimeoutMs = 30000;

        public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }
    }

    @Data
    public static class Embedding {
        @NotBlank private String baseUrl;
        private String apiKey = "";
        @NotBlank private String model;

        /**
         * Must equal the {@code vector(N)} width in V1__create_agent_tables.sql.
         * Nothing enforces that at compile time, so a mismatch shows up as a
         * Postgres error on the first write — which is the right moment for it
         * to be loud.
         */
        @Min(64) @Max(4096)
        private int dimension = 1024;

        @Positive private int connectTimeoutMs = 2000;
        @Positive private int readTimeoutMs = 15000;

        public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }
    }

    @Data
    public static class Ingestion {
        /**
         * Cosine below which an emitted tag label does not snap to a known tag
         * and becomes a proposal instead. High on purpose — a wrong snap is
         * invisible, an unsnapped label sits in a queue where someone sees it.
         */
        private double tagSnapThreshold = 0.82;

        /**
         * Cosine below which a facet value looks unlike anything approved on
         * that dim before. Usually means the model wrote the wrong dim's
         * content into the slot. Routed to review, never dropped silently.
         */
        private double dimValidationThreshold = 0.60;

        /** Confidence at or above which a facet may skip human review entirely. */
        private double autoApproveConfidence = 0.90;
    }
}
