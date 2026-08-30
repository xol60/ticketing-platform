package com.ticketing.agent.validation;

import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.domain.model.AgentEvent;
import com.ticketing.common.agent.Taxonomy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * The deterministic half of facet validation: no model, no vector, no network.
 *
 * <h3>Why these gates run first</h3>
 * Embedding a facet costs an inference call; rejecting one costs a string
 * comparison. Since most of what a small model fabricates is catchable without
 * any semantic understanding at all, the cheap checks belong in front — the
 * vector gates downstream then only ever see facets that are at least
 * genuinely quoted from the source.
 *
 * <p>The gates run in a fixed order, each one assuming its predecessors
 * passed. Grounding first, because a facet whose span is invented tells us
 * nothing worth measuring afterwards.
 *
 * <ol>
 *   <li><b>Shape</b> — non-empty value, dim inside the closed vocabulary.</li>
 *   <li><b>Grounding</b> — the cited span occurs verbatim in the source.</li>
 *   <li><b>Overlap</b> — the value is actually about the span it cites.</li>
 *   <li><b>Contradiction</b> — the claim survives comparison with what the
 *       event record already knows.</li>
 * </ol>
 *
 * <h3>What is deliberately not here</h3>
 * The model's own confidence score. It is emitted, and it is ignored: an 8B
 * model reports high confidence for a fabricated facet as readily as for a
 * sound one, so admitting it as evidence would launder precisely the failure
 * these gates exist to catch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FacetValidator {

    private final AgentProperties properties;

    /**
     * Words asserting a small, close, few-hundred-person setting. Checked
     * against a capacity band the service computed from the actual ticket
     * count, so a conflict is a fact against a claim, not two opinions.
     */
    private static final Set<String> SMALL_SCALE_MARKERS = Set.of(
            "intimate", "small", "tiny", "cosy", "cozy", "close",
            "handful", "few", "hundred", "club", "basement", "backroom"
    );

    /** The inverse: words asserting a crowd in the thousands. */
    private static final Set<String> LARGE_SCALE_MARKERS = Set.of(
            "stadium", "arena", "massive", "huge", "enormous", "thousand",
            "thousands", "packed", "sold-out", "vast", "sprawling"
    );

    /**
     * Validates every candidate for one event.
     *
     * <p>Each candidate is judged independently — one fabricated facet does
     * not condemn the rest. An event whose extraction was mostly invented
     * still keeps whatever it genuinely supported, and the rejection counts
     * make the ratio visible.
     */
    public List<ValidationOutcome> validate(AgentEvent event, List<FacetCandidate> candidates) {
        String source = event.getDescriptionRaw();

        // No source text means grounding is undecidable, not passed. Every
        // machine facet is unverifiable, so none may be trusted — this is the
        // §15.1 case, an event described too thinly to distil anything from.
        if (source == null || source.isBlank()) {
            log.debug("Event {} has no description — all {} candidates unverifiable",
                    event.getId(), candidates.size());
            return candidates.stream()
                    .map(c -> ValidationOutcome.reject(c, RejectionReason.SPAN_NOT_IN_SOURCE,
                            "event has no description_raw to ground against"))
                    .toList();
        }

        String foldedSource = TextNormalizer.fold(source);
        return candidates.stream().map(c -> validateOne(event, c, foldedSource)).toList();
    }

    private ValidationOutcome validateOne(AgentEvent event, FacetCandidate c, String foldedSource) {

        // ── 1. Shape ────────────────────────────────────────────────────────
        if (c.value() == null || c.value().isBlank()) {
            return ValidationOutcome.reject(c, RejectionReason.EMPTY_VALUE, null);
        }
        if (!Taxonomy.isKnownDim(c.dim())) {
            // Dropped rather than coerced to the nearest dim. A facet filed
            // under the wrong label is worse than an absent one: it competes
            // in comparisons it has no business being part of.
            return ValidationOutcome.reject(c, RejectionReason.UNKNOWN_DIM,
                    "dim '" + c.dim() + "' is not in the closed vocabulary");
        }

        // ── 2. Grounding ────────────────────────────────────────────────────
        var v = properties.getValidation();

        if (c.span() == null || c.span().isBlank()) {
            return ValidationOutcome.reject(c, RejectionReason.SPAN_NOT_IN_SOURCE,
                    "no span cited");
        }
        if (c.span().length() < v.getMinSpanChars()) {
            return ValidationOutcome.reject(c, RejectionReason.SPAN_TOO_SHORT,
                    "span is " + c.span().length() + " chars, minimum "
                            + v.getMinSpanChars());
        }
        if (!foldedSource.contains(TextNormalizer.fold(c.span()))) {
            return ValidationOutcome.reject(c, RejectionReason.SPAN_NOT_IN_SOURCE,
                    "cited span does not occur in the description");
        }

        // ── 3. Overlap ──────────────────────────────────────────────────────
        Set<String> valueWords = TextNormalizer.contentWords(c.value());
        if (valueWords.isEmpty()) {
            return ValidationOutcome.reject(c, RejectionReason.EMPTY_VALUE,
                    "value has no content words");
        }

        Set<String> spanWords = TextNormalizer.contentWords(c.span());
        long shared = valueWords.stream().filter(spanWords::contains).count();
        double overlap = (double) shared / valueWords.size();

        if (overlap < v.getMinSpanOverlap()) {
            return ValidationOutcome.reject(c, RejectionReason.LOW_SPAN_OVERLAP,
                    String.format("%.2f of value words appear in the span, minimum %.2f",
                            overlap, v.getMinSpanOverlap()));
        }

        // ── 4. Contradiction ────────────────────────────────────────────────
        String conflict = findScaleConflict(event, valueWords);
        if (conflict != null) {
            return ValidationOutcome.reject(c, RejectionReason.CONTRADICTS_EVENT, conflict);
        }

        return ValidationOutcome.accept(c);
    }

    /**
     * Compares a scale claim against the capacity band derived from ticket
     * count.
     *
     * <p>This is the only contradiction check that hard-rejects, and it earns
     * that because both sides are facts we own: the band is computed, not
     * described, so a mismatch is not a difference of interpretation.
     *
     * <p>Checks in the other direction were considered and left out. Indoor
     * versus outdoor, seated versus standing, and category-versus-format all
     * look checkable and are not — a Super Bowl description legitimately
     * discusses a concert, a stadium legitimately hosts a seated show. Each
     * would have rejected real facets from events that already have few.
     *
     * @return a description of the conflict, or null when there is none
     */
    private String findScaleConflict(AgentEvent event, Set<String> valueWords) {
        String band = event.getCapacityBand();
        if (band == null) return null;

        if ("large".equals(band)) {
            String hit = firstMatch(valueWords, SMALL_SCALE_MARKERS);
            if (hit != null) {
                return "claims '" + hit + "' but capacity_band is large";
            }
        } else if ("small".equals(band)) {
            String hit = firstMatch(valueWords, LARGE_SCALE_MARKERS);
            if (hit != null) {
                return "claims '" + hit + "' but capacity_band is small";
            }
        }
        return null;
    }

    /** Markers are stored unstemmed, so compare against the stem of each. */
    private static String firstMatch(Set<String> words, Set<String> markers) {
        for (String marker : markers) {
            if (words.contains(TextNormalizer.stem(marker))) return marker;
        }
        return null;
    }
}
