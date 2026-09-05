package com.ticketing.agent.vector;

import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.domain.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Maps a facet onto the tag vocabulary — the synonym-collapsing step.
 *
 * <h3>What this replaces</h3>
 * Tags used to come from a list the model emitted alongside its facets, snapped
 * to the catalogue by embedding the raw label. That had two problems. The label
 * was a slug-length string, far too short to embed meaningfully; and it was a
 * separate assertion from the facets, so nothing tied a tag to evidence in the
 * source.
 *
 * <p>Now the facet <em>is</em> the evidence, and the tag is derived from it. A
 * facet that survived grounding, overlap and contradiction checks is quoted
 * from the description; the tag it maps to inherits that provenance.
 *
 * <h3>Why value and span together</h3>
 * Measured on three real facets, each representation alone matched the correct
 * tag in only some cases while the pair matched in all three. The slugs below
 * are from the seed vocabulary, since retired, but the failure pattern is a
 * property of the representation rather than of those particular tags:
 *
 * <pre>
 *   facet                  value        span          value + span
 *   Mỹ Tâm / format        headliner ✗  headliner ✗   live-music  ✓
 *   Coldplay / physical    intimate  ✗  live-music ✓  live-music  ✓
 *   Metallica / setting    large-scale ✓ performing-arts ✗  large-scale ✓
 * </pre>
 *
 * They fail in opposite directions. {@code value} is often a word or two —
 * "arena" — with too little signal to place. {@code span} is raw source text
 * thick with specifics — "Xyloband", "snake pit", "360 in-the-round" — that pull
 * the vector toward the particular rather than the concept. Together they land
 * around fifteen to thirty words, which is where separation was measured to be
 * best.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagMatcher {

    /** How many candidates to return when a human is going to look at them. */
    public static final int REVIEW_CANDIDATES = 3;

    private final TagRepository  tagRepository;
    private final AgentProperties properties;

    /**
     * One candidate tag for a facet.
     *
     * @param score cosine against the tag's definition. Stored on the
     *              suggestion rather than compared to a threshold here — the
     *              threshold has not been set yet, and setting it before seeing
     *              the distribution on real data would be a guess.
     */
    public record Candidate(Integer tagId, String slug, double score) {}

    /** The text a facet is represented by when matched against tag definitions. */
    public static String representationOf(String value, String span) {
        if (span == null || span.isBlank()) return value;
        return value + ". " + span;
    }

    /**
     * Ranked tag candidates for a facet, all on the facet's own dim.
     *
     * <p>Empty when the dim carries no matchable tags, and also when nothing on
     * it clears {@code candidateFloor}. Both are the same answer to the
     * reviewer — no tag covers this facet — and both are answers the old
     * version could not give: it returned the nearest three whatever their
     * scores, so a facet on the wrong dim entirely still arrived carrying a
     * confident-looking label.
     */
    public List<Candidate> candidatesFor(String dim, String vectorLiteral, int topN) {
        if (dim == null || vectorLiteral == null) return List.of();

        return tagRepository.findCoveringInDim(dim, vectorLiteral, topN).stream()
                .map(r -> new Candidate(
                        ((Number) r[0]).intValue(),
                        (String) r[1],
                        ((Number) r[2]).doubleValue()))
                .toList();
    }

    /**
     * The query side's nearest tag, without the cross-dim comparison.
     *
     * <p>Deliberately not {@code candidatesFor}. A query facet is the model's
     * distillation of one request and is often a single word, so it lands much
     * closer to the floor of the space than an event facet does and the
     * comparison would discard most of them. The query path has its own
     * calibrated cut — {@code queryTagMatchThreshold} — and being wrong there
     * costs a worse ordering for one search, not a claim written into the
     * catalogue.
     */
    public Optional<Candidate> bestFor(String dim, String vectorLiteral) {
        if (dim == null || vectorLiteral == null) return Optional.empty();
        return tagRepository.findNearestInDim(dim, vectorLiteral, 1).stream()
                .map(r -> new Candidate(((Number) r[0]).intValue(), (String) r[1],
                                        ((Number) r[2]).doubleValue()))
                .findFirst();
    }
}
