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
 * {@code Taxonomy.TAGS} bootstraps an empty database and nothing more. Anything
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

    private record Snapshot(List<TagEntity> tags, Set<String> slugs, String promptBlock) {}

    /** Drops the cache. Call after creating, editing or retiring a tag. */
    public void invalidate() {
        snapshot = null;
    }

    public List<TagEntity> all()            { return current().tags(); }
    public Set<String>     slugs()          { return current().slugs(); }
    public boolean         isKnown(String s) { return s != null && current().slugs().contains(s); }

    /**
     * The tag catalogue as the query model sees it.
     *
     * <p>Only the query prompt gets this. Ingestion is asked for facets alone —
     * a tag is earned by matching a facet's vector against tag definitions on
     * its own dim, so it inherits that facet's verified span as evidence, while
     * a label the model asserts carries none.
     *
     * <p>The query side is the one place a slug must be nameable, because a
     * request to avoid something becomes an {@code excludeTags} entry. The
     * schema constrains that field to an enum of real slugs so nothing can be
     * invented, but an enum of bare slugs carries no meaning — the definitions
     * have to travel with it.
     */
    public String promptBlock() { return current().promptBlock(); }

    private Snapshot current() {
        Snapshot s = snapshot;
        if (s != null) return s;

        List<TagEntity> tags = tagRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(TagEntity::getSlug))
                .toList();

        StringBuilder sb = new StringBuilder(2048);
        sb.append("TAGS — slugs available to excludeTags.\n");
        for (TagEntity t : tags) {
            sb.append("  ").append(t.getSlug())
              .append(t.getDim() == null ? "" : " [" + t.getDim() + "]")
              .append(" — ").append(t.getDescription()).append('\n');
        }

        s = new Snapshot(tags,
                tags.stream().map(TagEntity::getSlug).collect(Collectors.toUnmodifiableSet()),
                sb.toString());
        snapshot = s;
        return s;
    }
}
