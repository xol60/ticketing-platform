package com.ticketing.agent.search;

import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.domain.repository.TagRepository;
import com.ticketing.agent.vector.EmbeddingService;
import com.ticketing.agent.vector.TagCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An exclusion is the only model output that acts as a hard filter, so it is
 * the only one that can delete a correct answer without leaving a trace. A
 * facet must quote its source and a tag assignment must survive review; an
 * exclusion removes events on the model's word alone.
 *
 * <p>The model no longer names a tag — it writes what the person ruled out, in
 * their words, and that phrase is resolved against the tag vectors afterwards.
 * These tests cover the three gates between a phrase and a deleted event.
 */
class QueryExtractorTest {

    /** Phrase -> [nearest similarity, runner-up similarity, nearest slug]. Measured. */
    private static final Map<String, Object[]> MEASURED = Map.of(
            "sports",           new Object[]{0.558, 0.521, "team-sport-fixture"},
            "a conference",     new Object[]{0.622, 0.549, "conference-keynote"},
            "too crowded",      new Object[]{0.549, 0.498, "stadium-crowd"},
            "a musical",        new Object[]{0.561, 0.548, "staged-drama"},
            "electronic music", new Object[]{0.492, 0.482, "live-music-concert"});

    private static QueryExtraction ground(QueryExtraction q, String message) throws Exception {
        TagRepository tags = mock(TagRepository.class);
        EmbeddingService embeddings = mock(EmbeddingService.class);
        // The vector literal stands in for the phrase, so the stub can key on it.
        when(embeddings.embedQuery(anyString())).thenAnswer(i -> i.getArgument(0));
        when(tags.nearestTwo(anyString())).thenAnswer(i -> {
            Object[] m = MEASURED.get((String) i.getArgument(0));
            return m == null ? List.of()
                    : List.of(new Object[]{m[2], m[0]}, new Object[]{"other-tag", m[1]});
        });

        Method mth = QueryExtractor.class.getDeclaredMethod(
                "groundExclusions", QueryExtraction.class, String.class);
        mth.setAccessible(true);
        return (QueryExtraction) mth.invoke(
                new QueryExtractor(null, mock(TagCatalog.class), tags, embeddings,
                                   new AgentProperties()),
                q, message);
    }

    private static QueryExtraction with(List<String> phrases) {
        return new QueryExtraction(QueryExtraction.Intent.FIND, null, List.of(), null,
                null, null, (BigDecimal) null, List.of(), phrases);
    }

    @Test
    @DisplayName("a request that rules nothing out produces no exclusions")
    void noNegationNoExclusions() throws Exception {
        // "basketball game" came back excluding eight of the ten tags — the
        // model listing what the event is not. The sentence rules out nothing.
        assertThat(ground(with(List.of("sports")), "basketball game").excludeTags()).isEmpty();
        assertThat(ground(with(List.of("a conference")), "soccer match").excludeTags()).isEmpty();
    }

    @Test
    @DisplayName("a phrase the vocabulary can name is excluded")
    void groundedPhraseResolves() throws Exception {
        assertThat(ground(with(List.of("sports")), "an evening out, not sports").excludeTags())
                .containsExactly("team-sport-fixture");
        assertThat(ground(with(List.of("a conference")),
                          "something in london, not a conference").excludeTags())
                .containsExactly("conference-keynote");
        assertThat(ground(with(List.of("too crowded")), "a concert but not too crowded")
                .excludeTags()).containsExactly("stadium-crowd");
    }

    @Test
    @DisplayName("a phrase no tag means is not forced onto the nearest one")
    void ungroundablePhraseIsDropped() throws Exception {
        // The two failures the gap gate exists for, and note what absolute
        // similarity would have said: "a musical" scores 0.561 against the tag
        // it would wrongly delete — higher than "sports" at 0.558 against the
        // tag it correctly deletes. Only the runner-up separates them.
        assertThat(ground(with(List.of("a musical")), "theatre but not a musical").excludeTags())
                .as("staged-drama covers ballet too, so excluding it deletes the answer")
                .isEmpty();
        assertThat(ground(with(List.of("electronic music")), "live music, nothing electronic")
                .excludeTags())
                .as("the nearest tag is live-music-concert — the thing they asked for")
                .isEmpty();
    }

    @Test
    @DisplayName("more separate things than the cap is an enumeration, discarded whole")
    void oversizedListIsDropped() throws Exception {
        var many = with(List.of("sports", "a conference", "too crowded", "a musical"));
        assertThat(ground(many, "live music, nothing else really").excludeTags()).isEmpty();
    }

    @Test
    @DisplayName("Vietnamese negation counts as negation")
    void vietnameseNegation() throws Exception {
        // Resolution is by vector, so the sentence and the vocabulary need not
        // share a language — only the phrase the model extracted has to embed.
        assertThat(ground(with(List.of("too crowded")),
                          "concert nhưng không quá đông").excludeTags())
                .containsExactly("stadium-crowd");
    }
}
