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
    @DisplayName("tag set is closed at 8 with unique kebab-case slugs")
    void tagSetIsClosed() {
        assertThat(Taxonomy.TAGS).hasSize(8);

        List<String> slugs = Taxonomy.TAGS.stream().map(Taxonomy.Tag::slug).toList();
        assertThat(slugs).doesNotHaveDuplicates();
        assertThat(slugs).allMatch(s -> s.matches("[a-z]+(-[a-z]+)*"),
                "slugs are kebab-case — they are used verbatim as DB keys and prompt tokens");

        // 6 carry a dim and can be matched against a facet; 2 are
        // exclusion-only. Forcing headliner or late-night into a dimension
        // would put them in competition with facets they do not describe.
        assertThat(Taxonomy.TAGS.stream().filter(Taxonomy.Tag::isMatchable)).hasSize(6);
        assertThat(Taxonomy.TAGS.stream().filter(t -> !t.isMatchable())).hasSize(2);
    }

    @Test
    @DisplayName("every matchable tag names a real dim and carries examples")
    void matchableTagsAreWellFormed() {
        Taxonomy.TAGS.stream().filter(Taxonomy.Tag::isMatchable).forEach(t -> {
            assertThat(Taxonomy.isKnownDim(t.dim()))
                    .as("tag '%s' claims dim '%s'", t.slug(), t.dim()).isTrue();
            assertThat(t.examples())
                    .as("tag '%s' needs examples — the slug alone is too short to embed",
                        t.slug())
                    .isNotBlank();
        });
    }

    @Test
    @DisplayName("embedding text is long enough to carry meaning")
    void embeddingTextIsSubstantial() {
        // Measured: embedding the slug "intimate" loses to "live-music" for the
        // query "a small room, close to the performer"; embedding name plus
        // definition plus examples wins it outright.
        Taxonomy.TAGS.stream().filter(Taxonomy.Tag::isMatchable).forEach(t ->
                assertThat(t.embeddingText()).hasSizeGreaterThan(120));
    }

    // The "a dim needs at least two tags" invariant used to be checked here and
    // is now checked at runtime by TagSynchronizer, against the database.
    //
    // It moved because its subject did. Taxonomy is a seed: it fills an empty
    // database and is then outranked by it, since a reviewer who finds no tag
    // fits a facet creates one and it takes effect at once. 'professional' is
    // exactly such a tag — it lives in the tag table with source = 'human' and
    // has no constant here, so this file cannot see the audience dim's second
    // answer and would fail on a vocabulary that is in fact correct.
    //
    // Do not restore it here. A Java test cannot observe the vocabulary the
    // system actually runs on.

    @Test
    @DisplayName("matchableOn returns only tags on that dim")
    void matchableOnFiltersByDim() {
        assertThat(Taxonomy.matchableOn(Taxonomy.DIM_SCALE))
                .extracting(Taxonomy.Tag::slug)
                .containsExactly(Taxonomy.TAG_LARGE_SCALE);   // 'broadcast' lives in the table
        assertThat(Taxonomy.matchableOn(Taxonomy.DIM_PARTICIPATION)).isEmpty();
        assertThat(Taxonomy.matchableOn(null)).isEmpty();
    }

    @Test
    @DisplayName("dim set is closed at 8, exactly 3 of them embedded")
    void dimSetIsClosed() {
        assertThat(Taxonomy.DIMS).hasSize(8);
        assertThat(Taxonomy.DIMS.stream().map(Taxonomy.Dim::name).toList())
                .doesNotHaveDuplicates();

        // The three dims users phrase preferences in, plus every dim a tag
        // claims. See EMBEDDED_DIMS for why the second half is not optional.
        assertThat(Taxonomy.EMBEDDED_DIMS).containsExactlyInAnyOrder(
                Taxonomy.DIM_FORMAT, Taxonomy.DIM_ATMOSPHERE, Taxonomy.DIM_PHYSICAL,
                Taxonomy.DIM_SCALE, Taxonomy.DIM_AUDIENCE);
    }

    @Test
    @DisplayName("every dim carrying a tag is embedded — otherwise the tag is unreachable")
    void everyTaggedDimIsEmbedded() {
        // The bug this locks down cost three tags entirely. A tag is matched by
        // comparing it against facets on its own dim; when that dim has no
        // vectors, the tag is not weakly matched, it simply never runs.
        // intimate, large-scale and family-kids each matched their test query
        // correctly and were assigned to zero events across 92.
        Taxonomy.TAGS.stream().filter(Taxonomy.Tag::isMatchable).forEach(t ->
                assertThat(Taxonomy.isEmbedded(t.dim()))
                        .as("tag '%s' lives on dim '%s', which must be embedded "
                          + "or the tag can never be suggested", t.slug(), t.dim())
                        .isTrue());
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
        assertThat(Taxonomy.isEmbedded(Taxonomy.DIM_SCALE))
                .as("scale carries intimate and large-scale").isTrue();
        assertThat(Taxonomy.isEmbedded(null)).isFalse();
    }

    @Test
    @DisplayName("the ingestion prompt names no tag")
    void tagsDoNotReachTheIngestionPrompt() {
        // The query side's half of this guard lives in TagCatalogTest, because
        // the query catalogue is built from the database and this module cannot
        // see it. The model is asked for facets only —
        // a tag is derived by matching a facet's vector against tag
        // definitions, so it inherits that facet's verified span as evidence,
        // where an asserted label would have none. Listing the catalogue here
        // also cost 2,223 characters on every event and grew with the
        // vocabulary, which is the scaling term the design removed.
        //
        // Ingestion is asked for facets only: a tag is earned by matching a
        // facet's vector against tag definitions on its own dim, so it inherits
        // that facet's verified span as evidence. An asserted label has none.
        String ingest = Taxonomy.promptBlock();
        assertThat(Taxonomy.TAG_SLUGS.stream().filter(ingest::contains))
                .as("the ingestion prompt must not name tags — it asks for facets only")
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
