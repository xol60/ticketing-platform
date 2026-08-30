package com.ticketing.agent.validation;

import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.domain.model.AgentEvent;
import com.ticketing.agent.validation.ValidationOutcome.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate that has to actually work.
 *
 * <p>Every case below is written against the real Coldplay description in
 * {@code ticket_db}, because the failures this guards against are specific to
 * real marketing copy: it is long, evocative, and full of sentences a model
 * can quote while writing something else entirely.
 *
 * <p>The fabrication cases are the point. A local 8B model does not produce
 * obvious nonsense — it produces well-formed, correctly-dimmed, plausible
 * facets about things the source never said. If those pass, the vector space
 * fills with confident fiction and every downstream ranking decision is built
 * on it.
 */
class FacetValidatorTest {

    /** Real copy from the Coldplay row, trimmed to the sentences under test. */
    private static final String SOURCE = """
            The Music of the Spheres World Tour is Coldplay's most ambitious live \
            production and one of the most environmentally responsible stadium tours \
            in music history, achieving 47% fewer carbon emissions than their 2016-17 \
            run. Compostable Xyloband LED wristbands — synced live to the music — \
            transform every audience member into part of the show. The setlist spans \
            three decades: Yellow, The Scientist, Fix You, A Sky Full of Stars.""";

    private FacetValidator validator;
    private AgentEvent event;

    @BeforeEach
    void setUp() {
        validator = new FacetValidator(new AgentProperties());
        event = AgentEvent.builder()
                .id("evt-coldplay")
                .name("Coldplay World Tour 2026")
                .status("OPEN")
                .startAt(Instant.now())
                .descriptionRaw(SOURCE)
                .capacityBand("large")
                .build();
    }

    private ValidationOutcome check(String dim, String value, String span) {
        return validator.validate(event, List.of(new FacetCandidate(dim, value, span))).get(0);
    }

    @Nested
    @DisplayName("facets genuinely read from the source")
    class Accepts {

        @Test
        @DisplayName("accepts a facet that rephrases the span it cites")
        void acceptsRephrasing() {
            // The whole reason overlap is measured on stems rather than exact
            // words: a good facet restates, it does not copy.
            ValidationOutcome r = check("physical",
                    "wristbands synced to the music",
                    "Xyloband LED wristbands — synced live to the music");

            assertThat(r.verdict()).isEqualTo(Verdict.ACCEPT);
        }

        @Test
        @DisplayName("tolerates typographic substitution inside the span")
        void toleratesPunctuationSwap() {
            // Model reproduces the sentence but emits ASCII hyphens where the
            // source has em dashes. It copied faithfully; the check must not
            // punish it for the encoding.
            ValidationOutcome r = check("physical",
                    "wristbands synced to the music",
                    "Xyloband LED wristbands - synced live to the music");

            assertThat(r.verdict()).isEqualTo(Verdict.ACCEPT);
        }

        @Test
        @DisplayName("tolerates collapsed whitespace and case differences")
        void toleratesWhitespaceAndCase() {
            ValidationOutcome r = check("format",
                    "setlist spanning three decades",
                    "The   SETLIST spans\n three decades");

            assertThat(r.verdict()).isEqualTo(Verdict.ACCEPT);
        }
    }

    @Nested
    @DisplayName("fabrication")
    class Rejects {

        @Test
        @DisplayName("rejects a fluent facet whose span was invented")
        void rejectsInventedSpan() {
            // Exactly what an 8B model produces: right dim, plausible prose,
            // confident — and about a venue this event does not have. Nothing
            // but grounding catches it.
            ValidationOutcome r = check("atmosphere",
                    "intimate candlelit room with relaxed bar service",
                    "the intimate candlelit room invites you to unwind");

            assertThat(r.verdict()).isEqualTo(Verdict.REJECT);
            assertThat(r.reason()).isEqualTo(RejectionReason.SPAN_NOT_IN_SOURCE);
        }

        @Test
        @DisplayName("rejects a real span paired with an unrelated claim")
        void rejectsDriftedValue() {
            // Second-order failure: quote something real, then write about
            // something else. Passes grounding, must fail on content.
            ValidationOutcome r = check("audience",
                    "strictly over-21 crowd, no children admitted",
                    "The setlist spans three decades");

            assertThat(r.verdict()).isEqualTo(Verdict.REJECT);
            assertThat(r.reason()).isEqualTo(RejectionReason.LOW_SPAN_OVERLAP);
        }

        @Test
        @DisplayName("rejects a span too short to be evidence")
        void rejectsShortSpan() {
            // "the music" appears in the source, so containment alone would
            // pass. A citation that short supports any claim at all.
            ValidationOutcome r = check("atmosphere", "loud and euphoric", "the music");

            assertThat(r.verdict()).isEqualTo(Verdict.REJECT);
            assertThat(r.reason()).isEqualTo(RejectionReason.SPAN_TOO_SHORT);
        }

        @Test
        @DisplayName("rejects a scale claim the ticket count disproves")
        void rejectsScaleContradiction() {
            // Grounded, and genuinely about the sentence it cites — the model
            // read the wristband line and concluded the show is small and
            // participatory. Overlap passes; only the capacity band computed
            // from 20,877 tickets contradicts it. Fact beats claim.
            ValidationOutcome r = check("scale",
                    "intimate show where every audience member takes part",
                    "transform every audience member into part of the show");

            assertThat(r.verdict()).isEqualTo(Verdict.REJECT);
            assertThat(r.reason()).isEqualTo(RejectionReason.CONTRADICTS_EVENT);
            assertThat(r.detail()).contains("capacity_band is large");
        }

        @Test
        @DisplayName("overlap is checked before contradiction, and says so")
        void overlapPrecedesContradiction() {
            // A facet that both drifts from its span AND contradicts the event
            // is attributed to the cheaper, more fundamental failure. Locking
            // the order here because the rejection reason drives the
            // diagnostic — a facet filed under the wrong cause sends the next
            // prompt fix in the wrong direction.
            ValidationOutcome r = check("scale",
                    "small intimate crowd",
                    "environmentally responsible stadium tours in music history");

            assertThat(r.verdict()).isEqualTo(Verdict.REJECT);
            assertThat(r.reason()).isEqualTo(RejectionReason.LOW_SPAN_OVERLAP);
        }

        @Test
        @DisplayName("rejects a dim outside the closed vocabulary")
        void rejectsUnknownDim() {
            // Dropped, never coerced to the nearest dim — a facet filed under
            // the wrong label competes in comparisons it has no part in.
            ValidationOutcome r = check("vibe",
                    "euphoric and communal",
                    "transform every audience member into part of the show");

            assertThat(r.verdict()).isEqualTo(Verdict.REJECT);
            assertThat(r.reason()).isEqualTo(RejectionReason.UNKNOWN_DIM);
        }

        @Test
        @DisplayName("rejects everything when the event has no description")
        void rejectsWhenSourceMissing() {
            // The §15.1 case — an event described as "Live music. 8pm. $40."
            // Grounding is undecidable, which is not the same as passed.
            event.setDescriptionRaw(null);

            List<ValidationOutcome> r = validator.validate(event, List.of(
                    new FacetCandidate("atmosphere", "lively", "somewhere"),
                    new FacetCandidate("format", "live set", "somewhere else")));

            assertThat(r).allMatch(ValidationOutcome::rejected);
            assertThat(r).allMatch(o -> o.reason() == RejectionReason.SPAN_NOT_IN_SOURCE);
        }
    }

    @Nested
    @DisplayName("independence")
    class Independence {

        @Test
        @DisplayName("keeps sound facets from an extraction that was mostly invented")
        void judgesEachCandidateSeparately() {
            // One bad facet must not condemn the batch. An event whose
            // extraction was 80% fiction still keeps what it genuinely
            // supported — and the counts make the ratio visible.
            List<ValidationOutcome> r = validator.validate(event, List.of(
                    new FacetCandidate("physical", "wristbands synced to the music",
                            "Xyloband LED wristbands — synced live to the music"),
                    new FacetCandidate("atmosphere", "quiet acoustic evening",
                            "a quiet acoustic evening in a small hall"),
                    new FacetCandidate("format", "setlist spanning three decades",
                            "The setlist spans three decades")));

            assertThat(r.get(0).verdict()).isEqualTo(Verdict.ACCEPT);
            assertThat(r.get(1).verdict()).isEqualTo(Verdict.REJECT);
            assertThat(r.get(2).verdict()).isEqualTo(Verdict.ACCEPT);
        }
    }

    @Nested
    @DisplayName("stemming")
    class Stemming {

        @Test
        @DisplayName("matches inflected forms across value and span")
        void stemsInflections() {
            assertThat(TextNormalizer.stem("singing")).isEqualTo(TextNormalizer.stem("sings"));
            assertThat(TextNormalizer.stem("stopped")).isEqualTo("stop");
            assertThat(TextNormalizer.stem("bodies")).isEqualTo("body");
        }

        @Test
        @DisplayName("does not over-stem short roots into collisions")
        void doesNotOverStem() {
            // Aggressive stemming merges unrelated words and quietly weakens
            // the overlap check — a facet then "matches" a span it shares
            // nothing real with.
            assertThat(TextNormalizer.stem("bass")).isEqualTo("bass");
            assertThat(TextNormalizer.stem("class")).isEqualTo("class");
        }
    }
}
