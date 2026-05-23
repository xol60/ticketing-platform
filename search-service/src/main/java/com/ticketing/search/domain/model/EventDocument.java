package com.ticketing.search.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.time.Instant;

/**
 * Elasticsearch projection of an {@code Event}.
 *
 * <p>Synced from ticket-service's Postgres source-of-truth via the
 * {@code event.search.indexed} Kafka topic. Only events with
 * {@code status = OPEN} are kept in the index — closed, cancelled and
 * completed events are removed so the public search surface stays small
 * and matches what a real user actually wants to discover.
 *
 * <h3>Multi-field {@code name}</h3>
 * The {@code name} field is mapped three ways at once:
 * <ul>
 *   <li><b>main</b>     — {@code text} with the standard analyzer, used by the
 *       multi-match full-text query.</li>
 *   <li><b>.keyword</b>  — exact-match keyword, available for future
 *       aggregations or sort.</li>
 *   <li><b>.edge</b>     — text analyzed with the custom
 *       {@code edge_ngram_analyzer} declared in {@code event-settings.json},
 *       used exclusively by the autocomplete endpoint.</li>
 * </ul>
 *
 * <h3>Schema evolution</h3>
 * Adding a new field is non-breaking — ES dynamic mapping creates it on first
 * write. Removing or retyping a field requires a reindex; out of scope here.
 *
 * <h3>{@code @Setting}</h3>
 * Points at a JSON file in classpath that defines the custom
 * {@code edge_ngram_analyzer} (min_gram=2, max_gram=20). Spring Data ES applies
 * this settings document the first time the index is created. If the index
 * already exists with different settings, this is a no-op — to roll a settings
 * change you must drop the index first (handled out-of-band via a reindex job).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "events", createIndex = true)
@Setting(settingPath = "elasticsearch/event-settings.json")
public class EventDocument {

    @Id
    private String id;

    // ── Searchable text fields ────────────────────────────────────────────────

    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "standard"),
            otherFields = {
                    @InnerField(suffix = "keyword", type = FieldType.Keyword),
                    @InnerField(suffix = "edge",
                            type = FieldType.Text,
                            analyzer = "edge_ngram_analyzer",
                            searchAnalyzer = "standard")
            })
    private String name;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String primaryArtist;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String venueName;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String venueCity;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String shortDescription;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String fullDescription;

    // ── Facet / filter fields (keyword = no analysis, exact match) ────────────

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private String genre;

    @Field(type = FieldType.Keyword)
    private String status;

    // ── Temporal fields (for filtering "upcoming this weekend" etc.) ──────────

    @Field(type = FieldType.Date)
    private Instant eventDate;

    @Field(type = FieldType.Date)
    private Instant salesOpenAt;

    @Field(type = FieldType.Date)
    private Instant salesCloseAt;
}
