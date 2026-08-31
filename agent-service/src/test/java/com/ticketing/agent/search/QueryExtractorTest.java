package com.ticketing.agent.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * excludeTags is the only model output that acts as a hard filter, so it is the
 * only one that can delete a correct answer without leaving a trace. A facet
 * must quote its source and a tag assignment must survive review; an exclusion
 * removes events on the model's word alone.
 */
class QueryExtractorTest {

    private static QueryExtraction ground(QueryExtraction q, String message) throws Exception {
        Method m = QueryExtractor.class.getDeclaredMethod(
                "groundExclusions", QueryExtraction.class, String.class);
        m.setAccessible(true);
        return (QueryExtraction) m.invoke(new QueryExtractor(null, null), q, message);
    }

    private static QueryExtraction with(List<String> excludes) {
        return new QueryExtraction(QueryExtraction.Intent.FIND, null, List.of(), null,
                null, null, (BigDecimal) null, List.of(), excludes);
    }

    @Test
    @DisplayName("a request that rules nothing out produces no exclusions")
    void noNegationNoExclusions() throws Exception {
        // "basketball game" came back excluding eight of the ten tags — the
        // model listing what the event is not. The sentence rules out nothing.
        assertThat(ground(with(List.of("live-music", "family-kids")), "basketball game").excludeTags())
                .isEmpty();
        assertThat(ground(with(List.of("conference-tech")), "soccer match").excludeTags())
                .isEmpty();
    }

    @Test
    @DisplayName("an enumeration of the vocabulary is discarded whole, not trimmed")
    void oversizedListIsDropped() throws Exception {
        // "live music, nothing electronic" excluded all ten tags, live-music
        // among them, and cut the candidate set from 64 events to 6. Keeping
        // the first two would only pick arbitrarily which right answers to lose.
        var q = with(List.of("live-music", "performing-arts", "sports", "conference-tech",
                             "family-kids", "professional", "large-scale", "broadcast",
                             "headliner", "late-night"));
        assertThat(ground(q, "live music, nothing electronic").excludeTags()).isEmpty();
    }

    @Test
    @DisplayName("a real exclusion survives")
    void genuineExclusionKept() throws Exception {
        assertThat(ground(with(List.of("large-scale")), "a concert but not too crowded").excludeTags())
                .containsExactly("large-scale");
        assertThat(ground(with(List.of("sports")), "an evening out, not sports").excludeTags())
                .containsExactly("sports");
        assertThat(ground(with(List.of("conference-tech")),
                          "something in london, not a conference").excludeTags())
                .containsExactly("conference-tech");
    }

    @Test
    @DisplayName("Vietnamese negation counts as negation")
    void vietnameseNegation() throws Exception {
        assertThat(ground(with(List.of("large-scale")), "concert nh\u01b0ng kh\u00f4ng qu\u00e1 \u0111\u00f4ng").excludeTags())
                .containsExactly("large-scale");
    }
}
