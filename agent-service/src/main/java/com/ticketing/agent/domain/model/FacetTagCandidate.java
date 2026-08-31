package com.ticketing.agent.domain.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * One tag a facet was compared against, with its score and placing.
 *
 * <p>Machine output, regenerated every time the facet is. It carries no
 * verdict: a reviewer's decision is stored on {@link EventTag}, keyed by event
 * and tag, so that it survives the re-ingest that deletes and recreates facets.
 *
 * @see com.ticketing.agent.ingest.TagSuggester
 */
@Entity
@Table(name = "facet_tag_candidate")
@IdClass(FacetTagCandidate.Key.class)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FacetTagCandidate {

    @Id
    @Column(name = "facet_id")
    private Long facetId;

    @Id
    @Column(name = "tag_id")
    private Integer tagId;

    /** Cosine between the facet's vector and the tag's definition vector. */
    @Column(nullable = false)
    private Float score;

    /** 1 is nearest. Stored so the review can show them in order without re-sorting. */
    @Column(nullable = false)
    private Short rank;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements java.io.Serializable {
        private Long facetId;
        private Integer tagId;
    }
}
