package com.ticketing.agent.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
 * <h3>Local inference</h3>
 * Both models run in Ollama on the same Docker network — nothing leaves the
 * host. That buys the project two things worth more than raw model quality:
 * it clones and runs with no API key, and no request carries event data to a
 * third party.
 *
 * <p>It also costs something, and the cost is the reason
 * {@link Validation} exists in the shape it does. An 8B model fabricates far
 * more readily than a frontier model, and it fabricates <em>fluently</em> — a
 * made-up facet reads exactly like a real one. Every guard downstream assumes
 * the extractor is untrustworthy by default.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    @Valid private Ollama     ollama     = new Ollama();
    @Valid private Validation validation = new Validation();

    @Data
    public static class Ollama {
        @NotBlank private String baseUrl;
        @Valid private Llm       llm       = new Llm();
        @Valid private Embedding embedding = new Embedding();

        @Data
        public static class Llm {
            @NotBlank private String model;

            /**
             * Zero, always. Ingestion is extraction, not writing — the same
             * description must yield the same facets on every run, or the
             * review queue fills with re-litigations of events someone
             * already approved.
             */
            @DecimalMin("0.0") @DecimalMax("1.0")
            private double temperature = 0.0;

            /** Context window. Must fit the taxonomy block plus the longest description. */
            @Positive private int numCtx = 8192;

            /**
             * Qwen3 emits {@code <think>} blocks unless told not to. They break
             * JSON-schema-constrained decoding and cost tokens for a task that
             * is transcription, not reasoning.
             */
            private boolean think = false;

            @Positive private int connectTimeoutMs = 2000;

            /**
             * Local inference on CPU can take a minute per event. This is not
             * a user-facing path at ingestion time, so the timeout is generous
             * — a spurious timeout costs a re-run of the whole extraction.
             */
            @Positive private int readTimeoutMs = 180_000;

            /** Retries when the model returns output that fails schema validation. */
            @Min(0) @Max(5) private int maxSchemaRetries = 2;
        }

        @Data
        public static class Embedding {
            @NotBlank private String model;

            /**
             * Must equal the {@code vector(N)} width in the migrations.
             * bge-m3 is 1024, which is why the schema was written at 1024.
             * Changing model to one of a different width is a full re-embed
             * behind a model_version cutover, not a config tweak.
             */
            @Min(64) @Max(4096) private int dimension = 1024;

            /**
             * Asymmetric-embedding prefixes. bge-m3 is trained to work without
             * them, so both default to empty — but they stay configurable
             * because most alternatives are not: nomic-embed-text needs
             * {@code search_query:} / {@code search_document:}, and feeding it
             * unprefixed text silently degrades every comparison rather than
             * failing.
             */
            private String queryPrefix    = "";
            private String documentPrefix = "";

            @Positive private int connectTimeoutMs = 2000;
            @Positive private int readTimeoutMs    = 60_000;
        }
    }

    /**
     * The gates every extracted facet passes before it can influence a search
     * result.
     *
     * <p>Ordered cheapest-and-most-decisive first. The three deterministic
     * gates need no vector and no model, so they run before anything is
     * embedded — most fabrication dies there, at zero inference cost.
     */
    @Data
    public static class Validation {

        /**
         * Minimum share of the facet's content words that must also appear —
         * after stemming — in the span it cites.
         *
         * <p>Catches the second-order failure: the model quotes a real
         * sentence and then writes a facet about something else. Set below 1.0
         * on purpose, because a good facet <em>should</em> rephrase rather
         * than copy — "crowd sings along" from "80,000 people singing every
         * word back" is exactly right, and shares only the stem "sing".
         */
        @DecimalMin("0.0") @DecimalMax("1.0")
        private double minSpanOverlap = 0.34;

        /** Spans shorter than this are too generic to constitute evidence. */
        @Positive private int minSpanChars = 12;

        /**
         * Cosine below which a facet value looks unlike anything approved on
         * that dim before — usually the model writing atmosphere content into
         * the format slot. Routed to review, never dropped: the dim may simply
         * be new.
         */
        @DecimalMin("0.0") @DecimalMax("1.0")
        private double dimThreshold = 0.60;

        /**
         * Cosine below which an emitted tag label does not snap to a known tag
         * and becomes a proposal. High on purpose — a wrong snap is invisible,
         * an unsnapped label sits in a queue where someone sees it.
         */
        @DecimalMin("0.0") @DecimalMax("1.0")
        private double tagSnapThreshold = 0.82;

        /**
         * Whether a facet clearing every deterministic gate may skip human
         * review.
         *
         * <p>Note what is absent: any use of the model's self-reported
         * confidence. An 8B model reports 0.95 for a fabricated facet as
         * readily as for a sound one, so treating that number as evidence
         * would launder exactly the failure the gates exist to catch.
         * Auto-approval is earned by passing deterministic checks or not at
         * all.
         */
        private boolean autoApproveOnAllGatesPass = true;
    }
}
