package com.ticketing.search.domain.repository;

import com.ticketing.search.domain.model.EventDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * Spring Data Elasticsearch repository for {@link EventDocument}.
 *
 * <p>Used exclusively by the indexing pipeline (Kafka consumer → upsert / delete).
 * Search-query construction is hand-rolled in {@code EventSearchService} via
 * {@code NativeQuery} / {@code ElasticsearchOperations} because the multi-match
 * + fuzzy + boost + filter combination we want is more expressive than
 * derived-name finders can offer.
 *
 * <p>{@code save(doc)} maps to {@code PUT _doc/{id}} on the ES side — fully
 * idempotent, which is why Kafka redelivery of the same indexing event is
 * safe to process more than once.
 */
public interface EventSearchRepository
        extends ElasticsearchRepository<EventDocument, String> {
}
