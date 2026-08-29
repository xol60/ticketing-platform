package com.ticketing.common.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the closed vocabularies the agent's two prompts share.
 *
 * <p>The tests that matter here are the last two. Everything else is shape
 * checking; {@code promptBlockCarriesEveryTag} and {@code promptBlockCarries
 * EveryDim} are the actual drift guard described in {@link Taxonomy}'s javadoc.
 * A tag or dim added to the constant lists but missing from the rendered
 * prompt block would leave the LLM unable to emit it — the ingestion output
 * would just quietly never contain that label, and no other test in the
 * codebase would notice.
 */
class TaxonomyTest {

    @Test
    @DisplayName("tag set is closed at 15 with unique kebab-case slugs")
    void tagSetIsClosed() {
        assertThat(Taxonomy.TAGS).hasSize(15);

        List<String> slugs = Taxonomy.TAGS.stream().map(Taxonomy.Tag::slug).toList();
        assertThat(slugs).doesNotHaveDuplicates();
        assertThat(slugs).allMatch(s -> s.matches("[a-z]+(-[a-z]+)*"),
                "slugs are kebab-case — they are used verbatim as DB keys and prompt tokens");

        // 10 category + 5 attribute, per the taxonomy design
        assertThat(Taxonomy.TAGS.stream()
                .filter(t -> t.kind() == Taxonomy.Tag.Kind.CATEGORY)).hasSize(10);
        assertThat(Taxonomy.TAGS.stream()
                .filter(t -> t.kind() == Taxonomy.Tag.Kind.ATTRIBUTE)).hasSize(5);
    }

    @Test
    @DisplayName("dim set is closed at 8, exactly 3 of them embedded")
    void dimSetIsClosed() {
        assertThat(Taxonomy.DIMS).hasSize(8);
        assertThat(Taxonomy.DIMS.stream().map(Taxonomy.Dim::name).toList())
                .doesNotHaveDuplicates();

        // Only the dims users actually phrase preferences in carry vectors.
        // Widening this set is a deliberate call backed by dim_frequency
        // telemetry, not something that should drift in unnoticed.
        assertThat(Taxonomy.EMBEDDED_DIMS).containsExactlyInAnyOrder(
                Taxonomy.DIM_FORMAT, Taxonomy.DIM_ATMOSPHERE, Taxonomy.DIM_PHYSICAL);
    }

    @Test
    @DisplayName("every tag and dim carries a description long enough to embed")
    void descriptionsAreEmbeddable() {
        // Tag descriptions are the bootstrap vector source before kNN voting
        // takes over, so a one-word description would produce a useless vector.
        assertThat(Taxonomy.TAGS).allSatisfy(t ->
                assertThat(t.description()).hasSizeGreaterThan(40));
        assertThat(Taxonomy.DIMS).allSatisfy(d ->
                assertThat(d.description()).hasSizeGreaterThan(40));
    }

    @Test
    @DisplayName("membership checks reject unknown values and tolerate null")
    void membershipChecks() {
        assertThat(Taxonomy.isKnownTag(Taxonomy.TAG_LIVE_MUSIC)).isTrue();
        assertThat(Taxonomy.isKnownTag("jazz-night")).isFalse();
        assertThat(Taxonomy.isKnownTag(null)).isFalse();

        assertThat(Taxonomy.isKnownDim(Taxonomy.DIM_ATMOSPHERE)).isTrue();
        assertThat(Taxonomy.isKnownDim("vibe")).isFalse();
        assertThat(Taxonomy.isKnownDim(null)).isFalse();

        assertThat(Taxonomy.isEmbedded(Taxonomy.DIM_FORMAT)).isTrue();
        assertThat(Taxonomy.isEmbedded(Taxonomy.DIM_SETTING)).isFalse();
        assertThat(Taxonomy.isEmbedded(null)).isFalse();
    }

    @Test
    @DisplayName("prompt block carries every tag slug — drift guard")
    void promptBlockCarriesEveryTag() {
        String block = Taxonomy.promptBlock();
        Set<String> missing = Taxonomy.TAG_SLUGS.stream()
                .filter(slug -> !block.contains(slug))
                .collect(Collectors.toSet());

        assertThat(missing)
                .as("a tag absent from the prompt block can never be emitted by the LLM")
                .isEmpty();
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
