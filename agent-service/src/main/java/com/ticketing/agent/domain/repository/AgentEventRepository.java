package com.ticketing.agent.domain.repository;

import com.ticketing.agent.domain.model.AgentEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface AgentEventRepository extends JpaRepository<AgentEvent, String> {

    /**
     * The hard filter: everything SQL can decide exactly.
     *
     * <p>City is an integer comparison, never a text match and never a vector —
     * a city name inside an embedding matches any description that mentions the
     * place in passing. Price compares against {@code price_min}, because a
     * budget rules an event out only when even its cheapest seat is over it.
     *
     * <p>The exclusion is {@code NOT EXISTS} over approved tags only. Hiding an
     * event on the strength of an unreviewed machine guess would shrink the
     * catalogue for a reason nobody checked.
     *
     * <p>{@code searchable} carries the review gate, so an OPEN event whose
     * facets nobody has accepted stays invisible here.
     *
     * @param excludeCount guards the IN clause when nothing is excluded, which
     *                     is the common case
     */
    // ORDER BY is required, not tidiness. Without one the database is free to
    // return rows in whatever order the plan produces, and the ranker's sort is
    // stable — so events on equal scores inherit that arbitrary order, and the
    // diversity cap then drops whichever of them happened to come second. Same
    // query, same data, different answer.
    @Query("""
            SELECT e FROM AgentEvent e
             WHERE e.searchable = true
               AND e.status = 'OPEN'
               AND e.startAt >= :from AND e.startAt < :to
               AND (:cityId IS NULL OR e.cityId = :cityId)
               AND (:priceMax IS NULL OR e.priceMin IS NULL OR e.priceMin <= :priceMax)
               AND (:excludeCount = 0 OR NOT EXISTS (
                     SELECT 1 FROM EventTag t
                      WHERE t.eventId = e.id
                        AND t.tagId IN :excludeTagIds
                        AND t.approvedAt IS NOT NULL))
             ORDER BY e.startAt, e.id
            """)
    List<AgentEvent> findCandidates(@Param("cityId") Integer cityId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to,
                                    @Param("priceMax") BigDecimal priceMax,
                                    @Param("excludeTagIds") Collection<Integer> excludeTagIds,
                                    @Param("excludeCount") int excludeCount);



    /**
     * The proper-noun path: a literal lookup across the three name columns.
     *
     * <p>Separate from the vector path on purpose. A name carries no mood and
     * distilling it destroys it — "Taylor Swift" embedded into a vibe vector
     * becomes a search for events that feel vaguely like a pop concert, which
     * returns everything except the four Taylor Swift shows. Names are matched,
     * not measured.
     *
     * <p>{@code ILIKE} rather than the Elasticsearch index search-service owns:
     * this is a filter over a candidate set already narrowed by city and date,
     * usually a few dozen rows, and reaching across a service boundary to
     * borrow an inverted index for that is a network hop to save a sequential
     * scan that was never slow. Accent-insensitivity is the known gap — a
     * Vietnamese name typed without diacritics will miss.
     */
    @Query("""
            SELECT e FROM AgentEvent e
             WHERE e.searchable = true
               AND e.status = 'OPEN'
               AND e.startAt >= :from AND e.startAt < :to
               AND (:cityId IS NULL OR e.cityId = :cityId)
               AND (LOWER(e.name)          LIKE LOWER(CONCAT('%', :term, '%'))
                 OR LOWER(e.primaryArtist) LIKE LOWER(CONCAT('%', :term, '%'))
                 OR LOWER(e.venueName)     LIKE LOWER(CONCAT('%', :term, '%')))
             ORDER BY e.startAt, e.id
            """)
    List<AgentEvent> findByName(@Param("term") String term,
                                @Param("cityId") Integer cityId,
                                @Param("from") Instant from,
                                @Param("to") Instant to);

    /**
     * The review queue, oldest first — a backlog here is the failure mode that
     * makes the whole catalogue look empty, because nothing becomes searchable
     * until someone works through it.
     */
    Page<AgentEvent> findBySearchableFalseOrderByIngestedAtAsc(Pageable pageable);

    /**
     * The number that decides whether vibe matching can exist at all.
     *
     * <p>An event whose source description says only "Live music. 8pm. $40."
     * yields no atmosphere and no format — no LLM can distil what was never
     * written. Such events are invisible to every vibe query and reachable
     * only through the hard filter.
     *
     * <p>If this ratio sits below roughly 60%, the problem is upstream in event
     * authoring, and no amount of prompt or ranking work will fix it. Measure
     * it before trusting anything else in this service.
     */
    @Query("""
            SELECT COUNT(e) FROM AgentEvent e
            WHERE (SELECT COUNT(f) FROM EventFacet f WHERE f.eventId = e.id) >= :minFacets
            """)
    long countWithAtLeastFacets(int minFacets);

    /**
     * Every genre value present in the catalogue.
     *
     * <p>Read rather than enumerated, because {@code genre} is a free
     * {@code VARCHAR(50)} written by whoever created the event — there is no
     * enum, no CHECK and no definition of it anywhere in the system. Its
     * fifteen tidy values today are an artefact of a seed script's constant
     * array, not of any discipline the column enforces, and one country-music
     * event adds a sixteenth without warning.
     *
     * <p>That is survivable only because of how the result is used: a match
     * boosts an event's rank and a miss costs nothing. A value nobody has seen
     * before simply fails to match, which is the correct answer for a genre the
     * catalogue does not carry.
     */
    @Query("SELECT DISTINCT e.genre FROM AgentEvent e WHERE e.genre IS NOT NULL")
    List<String> distinctGenres();
}
