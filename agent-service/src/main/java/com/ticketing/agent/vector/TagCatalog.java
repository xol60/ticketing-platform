package com.ticketing.agent.vector;

import com.ticketing.agent.domain.model.TagEntity;
import com.ticketing.agent.domain.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The tag vocabulary as it exists at runtime — read from the database.
 *
 * <h3>Why this is not {@code Taxonomy.TAGS}</h3>
 * The vocabulary is meant to grow. A reviewer who finds that no tag fits a
 * facet creates one, and it takes effect immediately; that is the whole point
 * of the review step, and {@code tag.source = 'human'} exists to protect such
 * rows from the startup synchroniser.
 *
 * <p>Half the system already honoured that. Matching reads {@code tag} through
 * {@link TagRepository#findNearestInDim}, the embedding backfill reads the
 * table and falls back to its columns for rows with no Java counterpart, and
 * the synchroniser skips human rows. The query side did not: it built the
 * {@code excludeTags} enum from {@code Taxonomy.TAGS}, listed the same constant
 * in the prompt, and validated returned slugs against it. A reviewer-created
 * tag would therefore match facets and be assigned to events correctly, and
 * then be impossible for anyone to exclude — present in the data, absent from
 * the vocabulary the model is allowed to speak.
 *
 * <p>So the boundary is: <b>Java seeds, the database decides.</b>
 * {@code Taxonomy.TAGS} is gone entirely — it bootstrapped an empty database and
 * nothing more, and a seed that outranks nothing is a second copy. Anything
 * reading tags while the service is running reads them from here.
 *
 * <h3>Cached, and why that is safe</h3>
 * This is on the path of every chat turn. The table changes only when a
 * reviewer acts, so the snapshot is built once and rebuilt on
 * {@link #invalidate()}. A stale read costs one turn without a newly-created
 * tag; a query per turn costs a round trip on every turn forever.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagCatalog {

    private final TagRepository tagRepository;

    private volatile Snapshot snapshot;

    private record Snapshot(List<TagEntity> tags, Set<String> slugs) {}

    /** Drops the cache. Call after creating, editing or retiring a tag. */
    public void invalidate() {
        snapshot = null;
    }

    public List<TagEntity> all()            { return current().tags(); }
    public Set<String>     slugs()          { return current().slugs(); }
    public boolean         isKnown(String s) { return s != null && current().slugs().contains(s); }

    // There is no promptBlock() any more, and its absence is the point.
    //
    // The query prompt used to carry this catalogue so the model could name a
    // slug for excludeTags — 3,135 characters at eighteen tags, linear in the
    // vocabulary, and 17KB at a hundred. It also changed the rest of the
    // extraction: shown the definitions, the model wrote slugs into facet
    // values, so "basketball game" arrived as format: "team-sport-fixture" and
    // embedded like nothing any facet is quoted from. Removing it measured +3
    // points over the evaluation set.
    //
    // A person now says what they are ruling out in their own words, and the
    // phrase is resolved against these same vectors afterwards. Neither prompt
    // in this service enumerates the vocabulary now, so neither grows with it.

    private Snapshot current() {
        Snapshot s = snapshot;
        if (s != null) return s;

        List<TagEntity> tags = tagRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(TagEntity::getSlug))
                .toList();

        s = new Snapshot(tags,
                tags.stream().map(TagEntity::getSlug).collect(Collectors.toUnmodifiableSet()));
        snapshot = s;
        return s;
    }
}
