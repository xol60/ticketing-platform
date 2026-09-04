package com.ticketing.common.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the closed vocabulary the agent's two prompts share.
 *
 * <p>The test that matters here is {@code promptBlockCarriesEveryDim}: it is
 * the drift guard described in {@link Taxonomy}'s javadoc. A dim added to the
 * constant list but missing from the rendered prompt block would leave the LLM
 * unable to emit it — the ingestion output would just quietly never contain
 * that label, and no other test in the codebase would notice.
 *
 * <p>Nothing here tests tags, because Java no longer defines any. The tag
 * vocabulary lives in the {@code tag} table and is written by reviewers, so a
 * test in this module could only assert against a copy that has no authority.
 * Its runtime invariants are checked where they can actually be observed:
 * "every dim needs at least two tags" in {@code TagEmbeddingBackfill} against
 * the database, and "a tag's dim must be embedded" in
 * {@code TagCurationService} at the moment of creation.
 */
class TaxonomyTest {

    @Test
    @DisplayName("dim set is closed at 8, exactly 5 of them embedded")
    void dimSetIsClosed() {
        assertThat(Taxonomy.DIMS).hasSize(8);
        assertThat(Taxonomy.DIMS.stream().map(Taxonomy.Dim::name).toList())
                .doesNotHaveDuplicates();

        // scale and audience are embedded because tags live on them. This set
        // used to be derived as a union with the Java tag list, after the two
        // silently stopped overlapping and left three tags unreachable; now
        // that the list is gone the membership is stated, and the curation
        // service refuses to put a tag anywhere outside it.
        assertThat(Taxonomy.EMBEDDED_DIMS).containsExactlyInAnyOrder(
                Taxonomy.DIM_FORMAT, Taxonomy.DIM_ATMOSPHERE, Taxonomy.DIM_PHYSICAL,
                Taxonomy.DIM_SCALE, Taxonomy.DIM_AUDIENCE);
    }

    @Test
    @DisplayName("every dim carries a description long enough to embed")
    void descriptionsAreEmbeddable() {
        assertThat(Taxonomy.DIMS).allSatisfy(d ->
                assertThat(d.description()).hasSizeGreaterThan(40));
    }

    @Test
    @DisplayName("membership checks reject unknown values and tolerate null")
    void membershipChecks() {
        assertThat(Taxonomy.isKnownDim(Taxonomy.DIM_ATMOSPHERE)).isTrue();
        assertThat(Taxonomy.isKnownDim("vibe")).isFalse();
        assertThat(Taxonomy.isKnownDim(null)).isFalse();

        assertThat(Taxonomy.isEmbedded(Taxonomy.DIM_FORMAT)).isTrue();
        assertThat(Taxonomy.isEmbedded(Taxonomy.DIM_SETTING)).isFalse();
        assertThat(Taxonomy.isEmbedded(Taxonomy.DIM_SCALE))
                .as("scale carries large-scale and broadcast").isTrue();
        assertThat(Taxonomy.isEmbedded(null)).isFalse();
    }

    @Test
    @DisplayName("prompt block carries every dim name and the leave-it-empty rule")
    void promptBlockCarriesEveryDim() {
        String block = Taxonomy.promptBlock();
        Set<String> missing = Taxonomy.DIM_NAMES.stream()
                .filter(dim -> !block.contains(dim))
                .collect(Collectors.toSet());

        assertThat(missing)
                .as("a dim absent from the prompt block can never be written or queried")
                .isEmpty();

        // The instruction that keeps invented facets out of the vector space.
        // Without it the model fills every dim to look complete, and those
        // fabricated values are indistinguishable from real ones once embedded.
        assertThat(block).containsIgnoringCase("leaving a dimension empty is correct");
    }
}
