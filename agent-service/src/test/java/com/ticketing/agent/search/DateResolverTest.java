package com.ticketing.agent.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Date resolution is the one part of this subsystem with no model, no vector
 * and no threshold in it — which makes it the cheapest thing here to test and
 * the least excusable to get wrong.
 *
 * <p>Both behaviours pinned below were live defects found by the evaluation set
 * rather than by review: a four-digit year was not understood at all, and an
 * expression that failed to parse silently searched a fortnight while saying
 * nothing at all searched two years.
 */
class DateResolverTest {

    private final DateResolver resolver = new DateResolver();

    /** Sunday 6 September 2026, the evaluation set's corpus snapshot. */
    private static final Instant NOW =
            LocalDate.of(2026, 9, 6).atStartOfDay(DateResolver.ZONE).plusHours(9).toInstant();

    private static LocalDate startDay(DateResolver.Window w) {
        return w.from().atZone(DateResolver.ZONE).toLocalDate();
    }

    /** The window is half-open at the far edge, so the last day it admits is one before. */
    private static LocalDate lastDay(DateResolver.Window w) {
        return w.to().atZone(DateResolver.ZONE).toLocalDate().minusDays(1);
    }

    @Test
    @DisplayName("a future year is the whole of that year")
    void futureYear() {
        DateResolver.Window w = resolver.resolve("2027", NOW);

        assertThat(startDay(w)).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(lastDay(w)).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(w.isDefault()).isFalse();
    }

    @Test
    @DisplayName("the current year starts now, not in January")
    void currentYear() {
        DateResolver.Window w = resolver.resolve("in 2026", NOW);

        assertThat(w.from()).isEqualTo(NOW);
        assertThat(lastDay(w)).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("a year beside a month names that month in that year, not the next one round")
    void monthAndYear() {
        DateResolver.Window w = resolver.resolve("december 2027", NOW);

        assertThat(startDay(w)).isEqualTo(LocalDate.of(2027, 12, 1));
        assertThat(lastDay(w)).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    @DisplayName("a bare month is the next time it comes round")
    void bareMonth() {
        assertThat(startDay(resolver.resolve("december", NOW))).isEqualTo(LocalDate.of(2026, 12, 1));
        // February 2026 has already passed on the snapshot date.
        assertThat(startDay(resolver.resolve("february", NOW))).isEqualTo(LocalDate.of(2027, 2, 1));
    }

    @Test
    @DisplayName("a year that is over resolves to an empty window rather than to a different year")
    void pastYear() {
        DateResolver.Window w = resolver.resolve("2019", NOW);

        assertThat(lastDay(w)).isEqualTo(LocalDate.of(2019, 12, 31));
        assertThat(w.to()).isBefore(NOW);
    }

    @Test
    @DisplayName("a quantity is not a year")
    void notAYear() {
        DateResolver.Window w = resolver.resolve("3 hours", NOW);

        assertThat(w.isDefault()).isTrue();
    }

    @Test
    @DisplayName("an unparseable expression must not search less than saying nothing would")
    void unparseableKeepsTheWideDefault() {
        DateResolver.Window blank   = resolver.resolve(null,        NOW, true);
        DateResolver.Window garbage = resolver.resolve("sometime?", NOW, true);

        assertThat(garbage.to()).isEqualTo(blank.to());
        assertThat(lastDay(garbage))
                .isEqualTo(LocalDate.of(2026, 9, 6).plusDays(DateResolver.LOOKUP_WINDOW_DAYS));
    }

    @Test
    @DisplayName("a browse still gets the fortnight, parsed or not")
    void browseIsUnaffected() {
        assertThat(lastDay(resolver.resolve("sometime?", NOW, false)))
                .isEqualTo(LocalDate.of(2026, 9, 6).plusDays(DateResolver.DEFAULT_WINDOW_DAYS));
    }

    @Test
    @DisplayName("an expression that parsed is never widened by the wide flag")
    void parsedExpressionOutranksTheDefault() {
        DateResolver.Window w = resolver.resolve("2027", NOW, true);

        assertThat(lastDay(w)).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    @DisplayName("relative phrases still resolve as before")
    void relativePhrasesUnchanged() {
        assertThat(startDay(resolver.resolve("next month", NOW))).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(lastDay(resolver.resolve("next month", NOW))).isEqualTo(LocalDate.of(2026, 10, 31));
        // 6 September 2026 is a Sunday, so "this weekend" is the one in progress.
        assertThat(startDay(resolver.resolve("this weekend", NOW))).isEqualTo(LocalDate.of(2026, 9, 5));
        ZonedDateTime tonight = NOW.atZone(DateResolver.ZONE);
        assertThat(lastDay(resolver.resolve("tonight", NOW))).isEqualTo(tonight.toLocalDate());
    }
}
