package com.ticketing.agent.search;

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
public class Ranker {

    // Weights sum to 1 so the score stays readable as a fraction. Semantic
    // dominates when a vibe was given; with none, its term is zero and the
    // other two carry the ordering by themselves.
    private static final double W_SEMANTIC = 0.60;
    private static final double W_TIME     = 0.25;
    private static final double W_POP      = 0.15;

    /** No more than this many results sharing a category and time of day. */
    private static final int MAX_PER_BUCKET = 2;

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

    public List<SearchResult.Scored> rank(List<AgentEvent> candidates,
                                          Map<String, Double> semanticScores,
                                          Instant now,
                                          int limit) {
        long horizon = horizonDays(candidates, now);
        List<SearchResult.Scored> scored = new ArrayList<>(candidates.size());

        for (AgentEvent e : candidates) {
            double semantic = semanticScores.getOrDefault(e.getId(), 0.0);
            double score = W_SEMANTIC * semantic
                         + W_TIME     * timeProximity(e.getStartAt(), now, horizon)
                         + W_POP      * popularity(e);
            scored.add(new SearchResult.Scored(e, score, semantic));
        }

        scored.sort(Comparator.comparingDouble(SearchResult.Scored::score).reversed());
        return applyDiversity(scored, limit);
    }

    /**
     * Walks the ranked list and skips anything that would be the third of its
     * kind, until the shortlist is full.
     *
     * <p>If the cap leaves the list short — a corpus where everything really is
     * the same kind of thing — the skipped rows come back in score order. A
     * short answer is worse than a repetitive one.
     */
    private List<SearchResult.Scored> applyDiversity(List<SearchResult.Scored> scored, int limit) {
        Map<String, Integer> seen = new HashMap<>();
        List<SearchResult.Scored> picked  = new ArrayList<>(limit);
        List<SearchResult.Scored> skipped = new ArrayList<>();

        for (SearchResult.Scored s : scored) {
            if (picked.size() >= limit) break;
            String bucket = bucketOf(s.event());
            if (seen.merge(bucket, 1, Integer::sum) <= MAX_PER_BUCKET) {
                picked.add(s);
            } else {
                skipped.add(s);
            }
        }
        for (SearchResult.Scored s : skipped) {
            if (picked.size() >= limit) break;
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
        List<SearchResult.Scored> out = new ArrayList<>(limit);
        for (int round = 0; out.size() < limit; round++) {
            boolean added = false;
            for (List<AgentEvent> list : byCategory.values()) {
                if (round < list.size() && out.size() < limit) {
                    AgentEvent e = list.get(round);
                    out.add(new SearchResult.Scored(
                            e, timeProximity(e.getStartAt(), now, MIN_HORIZON_DAYS), 0.0));
                    added = true;
                }
            }
            if (!added) break;
        }
        return out;
    }

    /** Category plus morning/afternoon/evening — the two axes a person notices first. */
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
