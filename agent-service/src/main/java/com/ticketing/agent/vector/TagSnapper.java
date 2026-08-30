package com.ticketing.agent.vector;

import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.domain.repository.TagProposalRepository;
import com.ticketing.agent.domain.repository.TagRepository;
import com.ticketing.common.agent.Taxonomy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Maps a free-form tag label onto the closed catalogue — or refuses to.
 *
 * <h3>The refusal is the feature</h3>
 * The extractor emits labels like "jazz night" or "eco-friendly tour". The
 * cheap move is to create a tag for each and carry on; the vocabulary then
 * grows at ingest speed, every filter built on it gets vaguer, and nobody
 * notices because nothing ever failed.
 *
 * <p>So an unrecognised label becomes a row in {@code tag_proposal} and the
 * tag set stays at fifteen. It grows only when a person decides it should,
 * looking at labels that have come up often and land nowhere near anything
 * existing.
 *
 * <h3>Why the threshold is high</h3>
 * A wrong snap is invisible — the event just carries a tag nobody questions,
 * and it starts appearing in filters it does not belong in. An unsnapped label
 * is visible, sitting in a queue. Given the choice, prefer the failure someone
 * will see.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagSnapper {

    private final TagRepository           tagRepository;
    private final TagProposalRepository   proposalRepository;
    private final EmbeddingService        embeddings;
    private final AgentProperties         properties;

    /**
     * @param label   what the extractor emitted
     * @param eventId recorded on the proposal so a reviewer can see the context
     * @return the canonical slug when the label snapped; empty when it became a
     *         proposal instead
     */
    public Optional<String> snap(String label, String eventId) {
        if (label == null || label.isBlank()) return Optional.empty();

        String normalised = label.trim().toLowerCase();

        // Exact hit first. Costs one index lookup and skips an embedding call
        // entirely — and with the catalogue in the prompt, the model emits a
        // real slug most of the time.
        if (Taxonomy.isKnownTag(normalised)) {
            return Optional.of(normalised);
        }

        String vector = embeddings.embedQuery(normalised);
        Object[] nearest = tagRepository.findNearest(vector);

        // No tag has a vector yet — day zero, before the bootstrap backfill has
        // run. Nothing can snap, so everything becomes a proposal rather than
        // being force-fitted against an empty catalogue.
        if (nearest == null || nearest.length < 2) {
            recordProposal(normalised, eventId, null, null);
            return Optional.empty();
        }

        String slug  = (String) nearest[0];
        float  score = ((Number) nearest[1]).floatValue();

        if (score >= properties.getValidation().getTagSnapThreshold()) {
            log.debug("Snapped '{}' → '{}' at {}", normalised, slug, score);
            return Optional.of(slug);
        }

        recordProposal(normalised, eventId, slug, score);
        return Optional.empty();
    }

    /**
     * Records the near miss along with what it almost matched.
     *
     * <p>The pair of numbers is what makes the periodic review quick: a label
     * seen fifty times at 0.45 is a real gap in the taxonomy, while one seen
     * twice at 0.80 is a paraphrase that should have snapped — and says the
     * threshold is a shade too high.
     */
    private void recordProposal(String label, String eventId, String nearestSlug, Float score) {
        proposalRepository.record(label, eventId, nearestSlug, score);
        log.debug("Tag '{}' did not snap (nearest {} at {}) — recorded as proposal",
                label, nearestSlug, score);
    }
}
