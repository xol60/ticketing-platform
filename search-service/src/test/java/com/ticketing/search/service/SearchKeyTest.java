package com.ticketing.search.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Behavioural contract for the {@link SearchKey} cache-key utilities.
 *
 * <p>These two functions decide how well the autocomplete cache fights
 * pollution. If {@link SearchKey#normalise(String)} regresses, hot
 * prefixes splinter across many keys (e.g. "Co" and "co" stop sharing an
 * entry). If {@link SearchKey#cacheable(String)} regresses, junk queries
 * leak into the cache and can crowd out useful entries despite
 * Caffeine's W-TinyLFU defence.
 */
@DisplayName("SearchKey — normalise + cacheable contracts")
class SearchKeyTest {

    // ── normalise ───────────────────────────────────────────────────────────

    @ParameterizedTest(name = "normalise(\"{0}\") == \"{1}\"")
    @CsvSource({
        // Basic lowercase
        "'Co',          'co'",
        "'COLDPLAY',    'coldplay'",
        // Trim leading/trailing whitespace
        "'  coldplay  ','coldplay'",
        "'\tcold\t',    'cold'",
        // Collapse internal whitespace
        "'led   zep',   'led zep'",
        "'led\t\tzep',  'led zep'",
        // Combined — case + trim + collapse all in one
        "'  Led   Zep  ', 'led zep'",
    })
    void normalise_lowercaseTrimAndCollapseWhitespace(String input, String expected) {
        assertThat(SearchKey.normalise(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("normalise(null) returns empty string — never throws")
    void normalise_nullSafe() {
        assertThat(SearchKey.normalise(null)).isEmpty();
    }

    // ── cacheable ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "cacheable(\"{0}\") rejected: too short or empty")
    @ValueSource(strings = { "", " ", "a", "  z  " })
    void cacheable_rejectsBelowMinLength(String input) {
        // We standardise on a 2-char minimum to match the UI's autocomplete threshold.
        assertThat(SearchKey.cacheable(input)).isFalse();
    }

    @Test
    @DisplayName("cacheable rejects null defensively")
    void cacheable_nullSafe() {
        assertThat(SearchKey.cacheable(null)).isFalse();
    }

    @Test
    @DisplayName("cacheable rejects strings longer than 64 chars (UUID paste, fuzz input)")
    void cacheable_rejectsOverlyLong() {
        String tooLong = "a".repeat(65);
        assertThat(SearchKey.cacheable(tooLong)).isFalse();

        // Boundary: exactly 64 chars is still allowed.
        String atLimit = "a".repeat(64);
        assertThat(SearchKey.cacheable(atLimit)).isTrue();
    }

    @ParameterizedTest(name = "cacheable(\"{0}\") rejected: no letters → pollution risk")
    @ValueSource(strings = {
            "12345",          // pure digits — UUID-looking
            "...",            // pure punctuation
            "    ",           // pure whitespace (normalises to empty too)
            "1 2 3 4 5",      // digits + spaces — still no letters
            "$$$$",
    })
    void cacheable_rejectsNoLetters(String input) {
        // Inputs with no letters never resolve to a real event name, so
        // caching them just wastes a slot. They go through to ES uncached.
        assertThat(SearchKey.cacheable(input)).isFalse();
    }

    @ParameterizedTest(name = "cacheable(\"{0}\") admitted: real prefix")
    @ValueSource(strings = {
            "co",                    // minimum length
            "coldplay",
            "Led Zep",               // mixed case + space
            "metallica m72",         // realistic 2-word
            "u2",                    // letter + digit, has at least one letter
            "lakers vs celtics",     // realistic 3-word
    })
    void cacheable_admitsRealisticPrefixes(String input) {
        assertThat(SearchKey.cacheable(input)).isTrue();
    }
}
