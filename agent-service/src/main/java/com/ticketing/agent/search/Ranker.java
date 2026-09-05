package com.ticketing.agent.search;

import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.domain.model.AgentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/**
 * Orders the candidates and decides which few are shown.
 *
 * <h3>Why diversity is a hard constraint, not a tiebreak</h3>
 * This design deliberately emits no per-result explanation, so the user has
 * nothing to go on but the rows themselves. If the top five are the same kind
 * of event at the same time of day, there is no axis to choose along and the
 * turn dead-ends. Capping repeats does the job an explanation would have done,
 * at no token cost — and it is why diversity overrides score rather than
 * breaking ties within it.
 */
@Slf4j
@Component
@lombok.RequiredArgsConstructor
public class Ranker {

    private final AgentProperties properties;

    // Weights sum to 1 so the score stays readable as a fraction. Semantic
    // dominates when a vibe was given; with none, its term is zero and the
    // other two carry the ordering by themselves.
    private static final double W_SEMANTIC = 0.60;
    private static final double W_TIME     = 0.25;
    private static final double W_POP      = 0.15;

    /** No more than this many results sharing a category and time of day. */
    private static final int MAX_PER_BUCKET = 2;

    /**
     * How many dates of the same show a shortlist may spend a slot on.
     *
     * <p>One, everywhere except a search by name. Five slots are the whole
     * answer, and a second date of a show the person has already been shown
     * adds nothing they could not ask for.
     *
     * <p>The category-and-time bucket above cannot enforce this and in fact
     * works against it: two dates of one show at different times of day land in
     * <em>different</em> buckets, so the cap that was meant to stop repetition
     * separates the repeats and waves both through. Asked for something to take
     * the children to in London, the shortlist came back as one correct answer
     * followed by the same technology conference three times.
     */
    private static final int MAX_PER_SHOW = 1;

    /**
     * Floor on the proximity horizon, for a search window shorter than this.
     *
     * <p>The horizon itself is the width of the window being searched, not a
     * constant. A fixed 60 days was calibrated against the browse fortnight and
     * became a bug the moment the window widened: over a two-year window every
     * event past two months scored zero on proximity, so a musical matching the
     * query at 0.7 lost to a Super Bowl matching at 0.3 that happened to be
     * next week. "Sooner is better" is only meaningful relative to the range
     * actually under consideration.
     */
    private static final long MIN_HORIZON_DAYS = 14;

    /**
     * Ranking for a search by name, where several dates of one act are the
     * answer rather than padding.
     *
     * <p>Someone typing "taylor swift" wants her dates. The show cap that keeps
     * a vibe search from spending four of five slots on one conference would
     * here delete exactly what was asked for.
     */
    public List<SearchResult.Scored> rankNameMatches(List<AgentEvent> candidates,
                                                     Instant now, int limit) {
        long horizon = horizonDays(candidates, now);
        List<SearchResult.Scored> scored = new ArrayList<>(candidates.size());
        for (AgentEvent e : candidates) {
            scored.add(new SearchResult.Scored(e,
                    W_TIME * timeProximity(e.getStartAt(), now, horizon)
                  + W_POP  * popularity(e), 0.0));
        }
        scored.sort(Comparator.comparingDouble(SearchResult.Scored::score).reversed()
                .thenComparing(s -> s.event().getId()));
        return applyDiversity(scored, limit, false);
    }

    /**
     * @param tagCarriers events carrying a tag the request resolved to
     * @param splittable  whether {@code tagCarriers} divides the list meaningfully.
     *                    When false every row is reported as matched: the request
     *                    could only be scored by cosine, which has no zero to
     *                    split on, and inventing a boundary there would hide
     *                    rows on a number that does not mean what it looks like.
     */
    public List<SearchResult.Scored> rank(List<AgentEvent> candidates,
                                          Map<String, Double> semanticScores,
                                          Set<String> tagCarriers,
                                          Set<String> namedGenres,
                                          boolean splittable,
                                          Instant now,
                                          int limit) {
        long horizon = horizonDays(candidates, now);
        List<SearchResult.Scored> scored = new ArrayList<>(candidates.size());

        for (AgentEvent e : candidates) {
            double semantic = semanticScores.getOrDefault(e.getId(), 0.0);
            double score = W_SEMANTIC * semantic
                         + W_TIME     * timeProximity(e.getStartAt(), now, horizon)
                         + W_POP      * popularity(e);
            // Added outside the weighted sum rather than folded into it. The
            // three weights above divide one unit of evidence between signals
            // that every event has; a genre match is evidence only some events
            // can carry, and renormalising for it would quietly rescale the
            // other three every time the column is null.
            if (e.getGenre() != null && namedGenres.contains(e.getGenre())) {
                score += properties.getValidation().getGenreBonus();
            }
            boolean matched = !splittable || tagCarriers.contains(e.getId());
            scored.add(new SearchResult.Scored(e, score, semantic, matched));
        }

        // The id tiebreak is not cosmetic. Ties are structural in this
        // catalogue — the same show runs in several cities with identical
        // facets, so identical scores are common rather than rare — and
        // applyDiversity turns an arbitrary order between two tied events into
        // a different result SET, not merely a different order: whichever of
        // them sorts second hits the per-bucket cap and is pushed out of the
        // shortlist entirely.
        //
        // Without it, 22 of 57 evaluation queries returned different results on
        // two runs of the same build against the same data. The extraction was
        // identical in all 57 — the model is deterministic at temperature 0 —
        // so the variation was entirely here. That is a correctness bug before
        // it is a measurement one: the same person asking the same question
        // twice got different answers, and no A/B comparison of ranking changes
        // meant anything while it stood.
        // Answering the request outranks scoring well. An event carrying the
        // tag the person asked for belongs above one that merely happens to be
        // sooner, and grouping them means a caller can cut the list where the
        // flag turns over instead of guessing at a score.
        scored.sort(Comparator.comparing(SearchResult.Scored::matched).reversed()
                .thenComparing(Comparator.comparingDouble(SearchResult.Scored::score).reversed())
                .thenComparing(s -> s.event().getId()));
        return applyDiversity(scored, limit, true);
    }

    /**
     * Walks the ranked list and skips anything that would be the third of its
     * kind, until the shortlist is full.
     *
     * <p>If the caps leave the list short — a corpus where everything really is
     * the same kind of thing — skipped rows come back in score order, because a
     * short answer is worse than a repetitive one. With one exception: the show
     * cap is never given back.
     *
     * <p>The two caps are not the same kind of rule. Category variety is a
     * preference — five football matches for a request that never mentioned
     * football is a worse answer than four, but it is still four answers.
     * A second date of a show already listed is not an answer at all: the
     * person has seen that show and can ask for its other dates. Refilling from
     * it spends a slot to tell them something they already know.
     *
     * <p>Measured: "high energy night out" came back with Super Bowl LX @ Tokyo
     * in two of its five slots, because the candidate pool was small, the show
     * cap correctly rejected the second date, and the refill loop then put it
     * straight back. The cap held only while there were enough distinct shows
     * to make it unnecessary.
     */
    private List<SearchResult.Scored> applyDiversity(List<SearchResult.Scored> scored,
                                                     int limit, boolean capShows) {
        Map<String, Integer> seen  = new HashMap<>();
        Map<String, Integer> shows = new HashMap<>();
        List<SearchResult.Scored> picked  = new ArrayList<>(limit);
        List<SearchResult.Scored> skipped = new ArrayList<>();

        for (SearchResult.Scored s : scored) {
            if (picked.size() >= limit) break;

            // The category bucket applies to filler only. It exists to add
            // variety where nothing distinguishes the rows — five different
            // football matches when the request said nothing about football.
            // A row that answers the request is not filler, and capping those
            // for variety drops answers to make room for events that answer
            // nothing: a request for a tech conference surfaced two of the four
            // conferences that matched, then Super Bowl and a musical.
            boolean roomInBucket = s.matched()
                    || seen.merge(bucketOf(s.event()), 1, Integer::sum) <= MAX_PER_BUCKET;
            // The show cap still applies to answers. Two dates of one
            // conference are one answer shown twice.
            boolean roomForShow  = !capShows
                    || shows.merge(showOf(s.event()), 1, Integer::sum) <= MAX_PER_SHOW;
            if (roomInBucket && roomForShow) {
                picked.add(s);
            } else {
                skipped.add(s);
            }
        }
        for (SearchResult.Scored s : skipped) {
            if (picked.size() >= limit) break;
            // Category variety is given back here; the show cap is not. A
            // duplicate show is never a better use of a slot than leaving it
            // empty, so this re-checks it against the same counter the first
            // pass used rather than waving the row through.
            if (capShows && shows.merge(showOf(s.event()), 1, Integer::sum) > MAX_PER_SHOW) {
                continue;
            }
            picked.add(s);
        }
        return picked;
    }

    /**
     * Turn 1 has no vibe and no constraints, so ranking by score returns the
     * five biggest events in the city — the home page, which the user has
     * already seen and left.
     *
     * <p>So the first turn maximises spread instead: one of each category. The
     * goal is not to be right, it is to get a click, because which row they
     * pick reveals more than any question would have.
     */
    public List<SearchResult.Scored> rankForFirstTurn(List<AgentEvent> candidates,
                                                      Instant now, int limit) {
        Map<String, List<AgentEvent>> byCategory = new LinkedHashMap<>();
        for (AgentEvent e : candidates) {
            byCategory.computeIfAbsent(
                    e.getCategory() == null ? "OTHER" : e.getCategory(),
                    k -> new ArrayList<>()).add(e);
        }
        byCategory.values().forEach(list ->
                list.sort(Comparator.comparingDouble((AgentEvent e) -> popularity(e)).reversed()));

        // Round-robin across categories: best of each, then second-best, so a
        // category with many events cannot crowd out one with few.
        // The round-robin alone repeats shows: a category's list is sorted by
        // popularity, so several dates of the same act sit next to each other
        // and successive rounds pick them one after another.
        List<SearchResult.Scored> out = new ArrayList<>(limit);
        Set<String> shown = new HashSet<>();
        for (int round = 0; out.size() < limit; round++) {
            boolean added = false;
            for (List<AgentEvent> list : byCategory.values()) {
                if (round < list.size() && out.size() < limit) {
                    AgentEvent e = list.get(round);
                    added = true;                       // the round did have a row to offer
                    if (!shown.add(showOf(e))) continue;
                    out.add(new SearchResult.Scored(
                            e, timeProximity(e.getStartAt(), now, MIN_HORIZON_DAYS), 0.0));
                }
            }
            if (!added) break;
        }
        return out;
    }

    /** Category plus morning/afternoon/evening — the two axes a person notices first. */
    /**
     * Identity of the show, as opposed to the performance.
     *
     * <p>The artist rather than the event name, because the name carries the
     * city — "CES Keynote 2027 @ London" and "@ Tokyo" are one conference and
     * would read as two shows. Somebody browsing without having named a city is
     * better served by five different things than by one thing in three places.
     */
    private static String showOf(AgentEvent e) {
        String artist = e.getPrimaryArtist();
        return artist != null && !artist.isBlank() ? artist
                : (e.getName() == null ? e.getId() : e.getName());
    }

    private static String bucketOf(AgentEvent e) {
        int hour = e.getStartAt().atZone(DateResolver.ZONE).getHour();
        String partOfDay = hour < 12 ? "morning" : hour < 17 ? "afternoon" : "evening";
        return (e.getCategory() == null ? "OTHER" : e.getCategory()) + ":" + partOfDay;
    }

    /**
     * The span the candidates actually occupy — the scale proximity is measured
     * against.
     *
     * <p>Taken from the results rather than the requested window, because the
     * requested window is often far wider than anything in it. Asking over two
     * years against a catalogue clustered in one month should still rank by
     * days, not treat every candidate as equally imminent.
     */
    private static long horizonDays(List<AgentEvent> candidates, Instant now) {
        long furthest = candidates.stream()
                .mapToLong(e -> Math.max(0, Duration.between(now, e.getStartAt()).toDays()))
                .max().orElse(MIN_HORIZON_DAYS);
        return Math.max(MIN_HORIZON_DAYS, furthest);
    }

    /** 1.0 for something happening now, decaying to 0 at the horizon. */
    private static double timeProximity(Instant startAt, Instant now, long horizonDays) {
        long days = Duration.between(now, startAt).toDays();
        if (days < 0) return 0.0;
        return Math.max(0.0, 1.0 - ((double) days / horizonDays));
    }

    /**
     * Stand-in for popularity until the hotness signal is wired in.
     *
     * <p>ticket-service already publishes {@code event.hotness.changed} with a
     * views-per-minute figure, which is a real demand signal and the right
     * input here. Until this service consumes it, capacity band is the honest
     * placeholder: bigger venues sell to more people. Documented as a
     * placeholder so nobody mistakes it for a measurement.
     */
    private static double popularity(AgentEvent e) {
        if (e.getCapacityBand() == null) return 0.3;
        return switch (e.getCapacityBand()) {
            case "large"  -> 1.0;
            case "medium" -> 0.6;
            default       -> 0.3;
        };
    }
}
