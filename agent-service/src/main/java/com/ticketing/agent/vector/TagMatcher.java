package com.ticketing.agent.vector;

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
 * Measured on three real facets from the catalogue, each representation matched
 * the correct tag in only some cases, while the pair matched in all three:
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

    private final TagRepository tagRepository;

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
     * <p>Empty when the dim carries no matchable tags — {@code participation}
     * and {@code duration} have none today, and a facet on those dims simply
     * suggests nothing rather than being forced onto an unrelated tag.
     */
    public List<Candidate> candidatesFor(String dim, String vectorLiteral, int topN) {
        if (dim == null || vectorLiteral == null) return List.of();

        return tagRepository.findNearestInDim(dim, vectorLiteral, topN).stream()
                .map(r -> new Candidate(
                        ((Number) r[0]).intValue(),
                        (String) r[1],
                        ((Number) r[2]).doubleValue()))
                .toList();
    }

    public Optional<Candidate> bestFor(String dim, String vectorLiteral) {
        return candidatesFor(dim, vectorLiteral, 1).stream().findFirst();
    }
}
