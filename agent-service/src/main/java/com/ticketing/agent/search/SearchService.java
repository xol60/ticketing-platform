package com.ticketing.agent.search;

import com.ticketing.agent.domain.model.AgentEvent;
import com.ticketing.agent.domain.repository.AgentEventRepository;
import com.ticketing.agent.domain.repository.CityAliasRepository;
import com.ticketing.agent.domain.repository.EventFacetRepository;
import com.ticketing.agent.domain.repository.TagRepository;
import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.domain.repository.EventTagRepository;
import com.ticketing.agent.vector.EmbeddingService;
import com.ticketing.agent.vector.TagMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/**
 * One search turn, end to end.
 *
 * <h3>The invariant</h3>
 * Every valid input leaves with events in hand, or with a message saying
 * exactly what was widened to find them. No branch ends in a bare question.
 * Asking "what kind of thing are you after?" before showing anything is a form
 * wearing a chat interface, and avoiding forms is why the user came here.
 *
 * <h3>Questions come after results, never instead of them</h3>
 * When the filter matches more than {@link #NARROW_THRESHOLD}, the caller
 * offers to narrow — with the real count attached, and after the shortlist.
 * The user has seen actual rows by then, knows what they are choosing between,
 * and can ignore the offer and still walk away with something.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    /** Above this many matches, offering to narrow is worth the interruption. */
    public static final int NARROW_THRESHOLD = 20;

    static final int SHORTLIST_SIZE = 5;

    /**
     * Below this many candidates, the search relaxes rather than answering.
     *
     * <p>Relaxing only on <em>zero</em> results turned out to be the wrong
     * trigger. Asked for "something calm and relaxing", the default fortnight
     * window left exactly two candidates — both American football — and the
     * ranker dutifully returned the better of two bad answers with no
     * indication anything had gone wrong. Two irrelevant results are closer to
     * a failure than to a success, and the user has no way to tell them apart.
     *
     * <p>Set to the shortlist size: below it the ranker cannot even fill the
     * list, so diversity has nothing to work with and the choice collapses.
     */
    static final int MIN_USEFUL_CANDIDATES = SHORTLIST_SIZE;

    /**
     * Escalating time horizons, in days added to the original window.
     *
     * <p>Progressive rather than a single wider default, because the right
     * window is a property of catalogue density, not a number anyone can pick
     * in advance. A dense city needs a fortnight; this corpus has 2 searchable
     * events inside a fortnight and 44 within a year. Stepping outward finds
     * the density instead of assuming it.
     */
    private static final int[] TIME_WIDENING_DAYS = {30, 90, 365};

    private final QueryExtractor       extractor;
    private final DateResolver         dateResolver;
    private final AgentEventRepository eventRepository;
    private final EventFacetRepository facetRepository;
    private final CityAliasRepository  aliasRepository;
    private final TagRepository        tagRepository;
    private final EmbeddingService     embeddings;
    private final TagMatcher           tagMatcher;
    private final EventTagRepository   eventTagRepository;
    private final AgentProperties      properties;
    private final Ranker               ranker;

    @Transactional(readOnly = true)
    public SearchResult search(String message, String fallbackCity) {
        return search(extractor.extract(message), fallbackCity);
    }

    /**
     * Searches from an already-extracted query.
     *
     * <p>The entry point multi-turn uses. An earlier version had the
     * conversation rebuild its accumulated state into a sentence and feed it
     * back through the extractor, so that one retrieval path served both — and
     * every turn re-parsed structured state out of prose it had just written.
     * The round trip lost a slot per turn: a city captured on turn one was gone
     * by turn two, because the reassembled sentence no longer looked like one
     * with a city in it.
     *
     * <p>Structured state now goes straight to the filter. The model reads the
     * person's words once and never has to read its own output back.
     */
    @Transactional(readOnly = true)
    public SearchResult search(QueryExtraction q, String fallbackCity) {
        Instant now = Instant.now();

        Integer cityId = resolveCity(q.city() != null ? q.city() : fallbackCity);
        List<Integer> excludeIds = resolveTags(q.excludeTags());

        // The browse fortnight belongs to one question only: "what's on". Any
        // expressed intent — a name, a city, a budget, or a described mood —
        // means the person is looking for something particular, and a fortnight
        // then hides most of the catalogue rather than focusing it.
        //
        // The numbers here are stark: 9 searchable events inside a fortnight
        // against 67 overall. A vibe query under the browse window was scoring
        // the same ten near-term events every time, which is why "something
        // calm" returned American football — not because the matching was
        // wrong, but because nothing calm was in range to match.
        boolean narrowed = !q.isBare();
        DateResolver.Window window = dateResolver.resolve(q.dateExpression(), now, narrowed);

        // A named artist, venue or show is a lookup, not a mood search. It
        // skips the vector path entirely — see AgentEventRepository.findByName
        // for why a name must be matched rather than measured.
        if (q.isLookup()) {
            List<AgentEvent> byName = eventRepository.findByName(
                    q.properNoun(), cityId, window.from(), window.to());
            if (!byName.isEmpty()) {
                return new SearchResult(
                        ranker.rankNameMatches(byName, now, SHORTLIST_SIZE),
                        byName.size(), List.of(), false);
            }
            // Nothing by that name. Fall through to the ordinary path rather
            // than returning empty: the person may have named something the
            // catalogue does not carry, and adjacent events beat nothing.
            log.debug("No event matched the name '{}' — falling back to a normal search",
                    q.properNoun());
        }

        List<String> relaxations = new ArrayList<>();
        BigDecimal priceMax = q.priceMax();

        List<AgentEvent> candidates = find(cityId, window, priceMax, excludeIds);

        // Relaxation, in a fixed order, every step announced. City is never
        // relaxed at any point: a show in the wrong city is not a worse answer,
        // it is a useless one, and returning it silently destroys trust in
        // every result that follows.
        //
        // The trigger is "too few to choose between", not "none at all" — see
        // MIN_USEFUL_CANDIDATES.

        if (candidates.size() < MIN_USEFUL_CANDIDATES && priceMax != null) {
            relaxations.add("bỏ giới hạn giá " + priceMax);
            priceMax = null;
            candidates = find(cityId, window, null, excludeIds);
        }

        for (int extraDays : TIME_WIDENING_DAYS) {
            if (candidates.size() >= MIN_USEFUL_CANDIDATES) break;
            DateResolver.Window wider = dateResolver.widen(window, extraDays);
            List<AgentEvent> widened = find(cityId, wider, priceMax, excludeIds);
            // Only keep a step that actually helped. Announcing a widening
            // that changed nothing tells the user their request was altered
            // for no reason, which is worse than saying nothing.
            if (widened.size() > candidates.size()) {
                window = wider;
                candidates = widened;
                relaxations.removeIf(r -> r.startsWith("mở rộng khoảng thời gian"));
                relaxations.add("mở rộng khoảng thời gian thêm " + extraDays + " ngày");
            }
        }

        if (candidates.size() < MIN_USEFUL_CANDIDATES && !excludeIds.isEmpty()) {
            relaxations.add("bỏ điều kiện loại trừ " + q.excludeTags());
            excludeIds = List.of();
            candidates = find(cityId, window, priceMax, excludeIds);
        }

        if (candidates.isEmpty()) {
            return SearchResult.empty(relaxations);
        }

        long total = candidates.size();

        // Turn 1: nothing was said, so ranking by score would return the five
        // biggest events — the home page the user already left. Spread instead.
        if (q.isBare()) {
            return new SearchResult(
                    ranker.rankForFirstTurn(candidates, now, SHORTLIST_SIZE),
                    total, relaxations, false);
        }

        Semantics semantic = scoreSemantically(q.vibeFacets(), candidates);
        return new SearchResult(
                ranker.rank(candidates, semantic.scores(), semantic.tagCarriers(),
                        semantic.splittable(), now, SHORTLIST_SIZE),
                total, relaxations, !semantic.scores().isEmpty());
    }

    private List<AgentEvent> find(Integer cityId, DateResolver.Window w,
                                  BigDecimal priceMax, List<Integer> excludeIds) {
        return eventRepository.findCandidates(
                cityId, w.from(), w.to(), priceMax,
                excludeIds.isEmpty() ? List.of(-1) : excludeIds, excludeIds.size());
    }

    /**
     * How well each candidate answers the request, on 0..1.
     *
     * <h3>Two signals, chosen per dim by whether a vocabulary exists</h3>
     * A query facet on a dim that has tags is resolved to a tag, and an event
     * either carries that tag or does not. That is a reviewed fact: a person
     * looked at the facet, its shortlist and their scores and said yes. A query
     * facet on a dim with no tags falls back to cosine against the stored
     * facets, because there is nothing else there to compare against.
     *
     * <p>The split is not a hedge, it is where the vocabulary reaches. Measured
     * over the 57-case evaluation set, 43 of 54 extracted query facets landed
     * on {@code format}, {@code scale} or {@code audience} — dims that carry
     * tags — and 11 on {@code atmosphere}, {@code physical},
     * {@code participation} and {@code duration}, which carry none. Scoring
     * those 11 as zero coverage would silently delete the vibe queries, which
     * are the ones the tags were meant to help.
     *
     * <h3>Why a tag beats a cosine where both are available</h3>
     * Cosine is recomputed on every request and its scale does not mean what it
     * looks like: two unrelated phrases on the same dim score 0.452, which
     * reads as "somewhat relevant" and is not. Nothing separates a real match
     * from that floor, so ranking by it spreads a little credit across the
     * whole corpus. Tag membership has no floor — an event carries the tag or
     * it does not.
     *
     * <p>Each query facet contributes one unit either way and the sum is
     * divided by the number of <em>query</em> facets, so the denominator is
     * constant across the result set: no event is rewarded or punished for how
     * richly it happens to be described, only for how well it answers what was
     * asked.
     */
    private Semantics scoreSemantically(List<FacetQuery> vibe, List<AgentEvent> candidates) {
        if (vibe.isEmpty() || candidates.isEmpty()) return Semantics.none();

        List<String> ids = candidates.stream().map(AgentEvent::getId).toList();
        Map<String, Double> summed = new HashMap<>();
        Map<String, Integer> coverage = new HashMap<>();
        int resolvedFacets = 0;
        double threshold = properties.getValidation().getTagMatchThreshold();

        for (FacetQuery f : vibe) {
            String vector;
            try {
                vector = embeddings.embedQuery(f.value());
            } catch (Exception e) {
                // One dim failing to embed should cost that dim, not the search.
                log.warn("Could not embed query facet on dim {}: {}", f.dim(), e.getMessage());
                continue;
            }

            var tag = tagMatcher.bestFor(f.dim(), vector);
            if (tag.isPresent() && tag.get().score() >= threshold) {
                List<Object[]> carriers =
                        eventTagRepository.findApprovedPairs(List.of(tag.get().tagId()), ids);

                // A tag no candidate carries cannot rank them. Scoring it as
                // coverage would give every event zero and silently delete the
                // facet — worse than not resolving it at all, because the
                // person did express something and it stops counting.
                //
                // This is not hypothetical: "somewhere I can learn something"
                // resolves to workshop at 0.594, which is correct, and workshop
                // is carried by no event in the corpus. The query lost its only
                // real signal and dropped from one right answer to none. Six of
                // the thirteen matchable tags are currently carried by nothing.
                if (carriers.isEmpty()) {
                    log.debug("Query facet {}:'{}' resolved to tag {} ({}), which no candidate "
                                    + "carries — scoring by facet cosine instead",
                            f.dim(), f.value(), tag.get().slug(), tag.get().score());
                } else {
                    log.debug("Query facet {}:'{}' resolved to tag {} ({}), carried by {} candidates",
                            f.dim(), f.value(), tag.get().slug(), tag.get().score(), carriers.size());
                    resolvedFacets++;
                    for (Object[] row : carriers) {
                        summed.merge((String) row[0], 1.0, Double::sum);
                        coverage.merge((String) row[0], 1, Integer::sum);
                    }
                    continue;
                }
            }

            // No vocabulary on this dim, or nothing near enough on it. Falling
            // through to cosine rather than scoring zero: a facet the tags
            // cannot represent is still evidence, and dropping it would make
            // the search worse on exactly the dims the tags do not cover.
            log.debug("Query facet {}:'{}' matched no tag ({}) — scoring by facet cosine",
                    f.dim(), f.value(),
                    tag.map(c -> c.slug() + " " + c.score()).orElse("no tag on dim"));
            for (Object[] row : facetRepository.bestMatchPerEvent(f.dim(), vector, ids)) {
                summed.merge((String) row[0], ((Number) row[1]).doubleValue(), Double::sum);
            }
        }

        Map<String, Double> out = new HashMap<>(summed.size());
        summed.forEach((id, sum) -> out.put(id, sum / vibe.size()));

        // An answer covers everything the request could be resolved into, not
        // merely something. "basketball game" resolves to sports AND
        // large-scale, and treating one of the two as enough labelled AWS
        // re:Invent and a Taylor Swift date as answers to it — both are
        // large-scale and neither is a basketball game.
        //
        // Partial cover still ranks: the score is k/N, so a facet matched is a
        // facet rewarded. Only the label is strict, because the label is what
        // tells a reader "this is what you asked for".
        final int required = resolvedFacets;
        Set<String> answers = coverage.entrySet().stream()
                .filter(e -> e.getValue() >= required)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        return new Semantics(out, answers, required > 0);
    }

    /**
     * Scores, plus who genuinely answered the request.
     *
     * @param scores       0..1 per event, the semantic term of the rank
     * @param tagCarriers  events covering every tag the request resolved to
     * @param splittable   whether {@code tagCarriers} is a meaningful division.
     *                     False when no facet resolved to a tag any candidate
     *                     carries, in which case the request could only be
     *                     scored by cosine — and cosine has no zero to split on
     */
    private record Semantics(Map<String, Double> scores, Set<String> tagCarriers, boolean splittable) {
        static Semantics none() { return new Semantics(Map.of(), Set.of(), false); }
    }

    /**
     * Resolves a typed city name through the alias table.
     *
     * <p>Folded the same way aliases were generated at ingest — lowercase,
     * accents stripped, punctuation gone — so "hanoi", "ha noi" and "Hà Nội"
     * all arrive at the same row. Returns null when it does not resolve, which
     * means an unconstrained search rather than an error: a misspelled city is
     * not worth failing a turn over.
     */
    private Integer resolveCity(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String folded = Normalizer.normalize(raw.toLowerCase().trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return aliasRepository.findById(folded)
                .map(a -> a.getCityId())
                .orElseGet(() -> aliasRepository.findById(folded.replace(" ", ""))
                        .map(a -> a.getCityId())
                        .orElseGet(() -> {
                            log.debug("City '{}' did not resolve — searching unconstrained", raw);
                            return null;
                        }));
    }

    private List<Integer> resolveTags(List<String> slugs) {
        return slugs.stream()
                .map(tagRepository::findBySlug)
                .flatMap(Optional::stream)
                .map(t -> t.getId())
                .toList();
    }
}
