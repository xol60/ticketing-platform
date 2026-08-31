package com.ticketing.agent.vector;

import com.ticketing.agent.domain.model.TagEntity;
import com.ticketing.agent.domain.repository.TagRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * The query vocabulary must be whatever the database holds.
 *
 * <p>The bug these lock down: the query side built its {@code excludeTags} enum
 * and its prompt catalogue from {@code Taxonomy.TAGS} while matching read the
 * {@code tag} table. A reviewer-created tag was therefore assigned to events
 * correctly and could never be excluded by anyone — present in the data,
 * absent from the vocabulary the model was allowed to speak.
 */
class TagCatalogTest {

    private static TagEntity tag(String slug, String dim, String desc) {
        return TagEntity.builder().id(slug.hashCode()).slug(slug).name(slug)
                .dim(dim).description(desc).source("taxonomy").build();
    }

    @Test
    @DisplayName("a tag that exists only in the database is known and described")
    void databaseOnlyTagIsInTheVocabulary() {
        TagRepository repo = mock(TagRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                tag("live-music", "format", "A live musical performance."),
                // No Java constant for this one — it is what a reviewer adds.
                tag("professional", "audience", "An event aimed at people attending for work.")));

        TagCatalog catalog = new TagCatalog(repo);

        assertThat(catalog.isKnown("professional"))
                .as("a reviewer-created tag must be excludable the moment it exists")
                .isTrue();
        assertThat(catalog.slugs()).containsExactlyInAnyOrder("live-music", "professional");
        assertThat(catalog.promptBlock())
                .as("an enum of bare slugs carries no meaning — definitions travel with it")
                .contains("professional [audience]")
                .contains("An event aimed at people attending for work.");
        assertThat(catalog.isKnown("jazz-night")).isFalse();
        assertThat(catalog.isKnown(null)).isFalse();
    }

    @Test
    @DisplayName("the snapshot is cached, and invalidate rebuilds it")
    void cachedUntilInvalidated() {
        TagRepository repo = mock(TagRepository.class);
        when(repo.findAll()).thenReturn(List.of(tag("live-music", "format", "d")));
        TagCatalog catalog = new TagCatalog(repo);

        catalog.slugs();
        catalog.promptBlock();
        catalog.isKnown("live-music");
        verify(repo, times(1)).findAll();   // on every chat turn otherwise

        when(repo.findAll()).thenReturn(List.of(
                tag("live-music", "format", "d"), tag("new-tag", "format", "d")));
        assertThat(catalog.isKnown("new-tag"))
                .as("without invalidation a newly created tag stays invisible")
                .isFalse();

        catalog.invalidate();
        assertThat(catalog.isKnown("new-tag")).isTrue();
    }
}
