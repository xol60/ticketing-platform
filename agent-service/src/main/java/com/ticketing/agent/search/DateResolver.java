package com.ticketing.agent.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * Turns a relative phrase into a concrete window.
 *
 * <h3>Why Java and not the model</h3>
 * A model asked what date Saturday falls on will answer confidently and often
 * wrongly, and the error is invisible: a search for "this weekend" quietly
 * returns next month. Date arithmetic needs a real clock and a real zone, both
 * of which live here. The model's only job is to hand back the person's own
 * words.
 *
 * <p>The zone is Asia/Ho_Chi_Minh because "tonight" means tonight where the
 * user is, not where the server happens to run. Events are stored as instants
 * and compared as instants; the zone only decides where a local day begins.
 */
@Slf4j
@Component
public class DateResolver {

    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** No date given on a browse: the next two weeks, ranked by proximity. */
    static final int DEFAULT_WINDOW_DAYS = 14;

    /**
     * No date given, but the person named something specific.
     *
     * <p>A fortnight is the right default for "what's on" and the wrong one for
     * "taylor swift". Someone naming an artist wants that artist's shows, and a
     * catalogue whose events sit mostly a year out returns nothing under the
     * browse window — the search looks broken while the events sit there,
     * searchable, eleven months away.
     */
    static final int LOOKUP_WINDOW_DAYS = 730;

    /** A resolved window, plus whether it came from a default rather than the user. */
    public record Window(Instant from, Instant to, boolean isDefault) {}

    public Window resolve(String expression) {
        return resolve(expression, Instant.now());
    }

    /**
     * Resolves with a default window sized to the kind of question being asked.
     *
     * @param wide true for a lookup or an otherwise-narrowed query, where the
     *             browse fortnight would hide most of the catalogue
     */
    public Window resolve(String expression, Instant now, boolean wide) {
        if (expression != null && !expression.isBlank()) return resolve(expression, now);
        LocalDate today = now.atZone(ZONE).toLocalDate();
        int days = wide ? LOOKUP_WINDOW_DAYS : DEFAULT_WINDOW_DAYS;
        return new Window(now, endOf(today.plusDays(days)), true);
    }

    /** Overload taking the clock explicitly, so the resolution is testable. */
    Window resolve(String expression, Instant now) {
        LocalDate today = now.atZone(ZONE).toLocalDate();

        if (expression == null || expression.isBlank()) {
            return new Window(now, endOf(today.plusDays(DEFAULT_WINDOW_DAYS)), true);
        }

        String e = expression.toLowerCase(Locale.ROOT).trim();

        // Order matters: "this weekend" must be tested before "this", and
        // "tomorrow" before "today" would be a bug waiting to happen if either
        // were a prefix of the other.
        if (e.contains("tonight") || e.contains("today")) {
            return new Window(now, endOf(today), false);
        }
        if (e.contains("tomorrow")) {
            LocalDate d = today.plusDays(1);
            return new Window(startOf(d), endOf(d), false);
        }
        if (e.contains("weekend")) {
            // Saturday through Sunday. On a Saturday or Sunday "this weekend"
            // means the one happening now, not the next one.
            LocalDate saturday = today.getDayOfWeek() == DayOfWeek.SUNDAY
                    ? today.minusDays(1)
                    : today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
            return new Window(startOf(saturday), endOf(saturday.plusDays(1)), false);
        }
        if (e.contains("next week")) {
            LocalDate monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            return new Window(startOf(monday), endOf(monday.plusDays(6)), false);
        }
        if (e.contains("this week")) {
            LocalDate sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            return new Window(now, endOf(sunday), false);
        }
        if (e.contains("next month")) {
            LocalDate first = today.plusMonths(1).withDayOfMonth(1);
            return new Window(startOf(first), endOf(first.with(TemporalAdjusters.lastDayOfMonth())), false);
        }
        if (e.contains("this month")) {
            return new Window(now, endOf(today.with(TemporalAdjusters.lastDayOfMonth())), false);
        }

        DayOfWeek dow = matchDayOfWeek(e);
        if (dow != null) {
            LocalDate d = today.with(TemporalAdjusters.nextOrSame(dow));
            return new Window(startOf(d), endOf(d), false);
        }

        Month month = matchMonth(e);
        if (month != null) {
            // A bare month name means the next time that month comes round —
            // "events in february" asked in August means next February, never
            // the one that already passed.
            LocalDate first = today.withMonth(month.getValue()).withDayOfMonth(1);
            if (!first.isAfter(today)) first = first.plusYears(1);
            return new Window(startOf(first), endOf(first.with(TemporalAdjusters.lastDayOfMonth())), false);
        }

        log.debug("Unrecognised date expression '{}' — falling back to the default window", expression);
        return new Window(now, endOf(today.plusDays(DEFAULT_WINDOW_DAYS)), true);
    }

    /** Widens the far edge, leaving the near edge alone. Used by the relaxation chain. */
    public Window widen(Window w, int extraDays) {
        return new Window(w.from(), w.to().plus(Duration.ofDays(extraDays)), w.isDefault());
    }

    private static Instant startOf(LocalDate d) { return d.atStartOfDay(ZONE).toInstant(); }
    private static Instant endOf(LocalDate d)   { return d.plusDays(1).atStartOfDay(ZONE).toInstant(); }

    private static DayOfWeek matchDayOfWeek(String e) {
        for (DayOfWeek d : DayOfWeek.values()) {
            if (e.contains(d.name().toLowerCase(Locale.ROOT))) return d;
        }
        return null;
    }

    private static Month matchMonth(String e) {
        for (Month m : Month.values()) {
            String name = m.name().toLowerCase(Locale.ROOT);
            // Full name, or the three-letter abbreviation as a whole word —
            // substring alone would let "march" match inside "marchers".
            if (e.contains(name) || e.matches(".*\\b" + name.substring(0, 3) + "\\b.*")) return m;
        }
        return null;
    }
}
