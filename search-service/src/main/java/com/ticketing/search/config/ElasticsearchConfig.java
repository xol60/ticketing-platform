package com.ticketing.search.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

/**
 * Wires Spring Data Elasticsearch against the configured cluster.
 *
 * <p>Extends {@link ElasticsearchConfiguration} (the modern Java client variant —
 * not the deprecated {@code AbstractElasticsearchConfiguration} which used the
 * HLRC) so we pick up the official 8.x Java API client under the hood. The
 * {@code @EnableElasticsearchRepositories} annotation scans {@code com.ticketing.search.domain.repository}
 * for {@link org.springframework.data.elasticsearch.repository.ElasticsearchRepository}
 * interfaces — keeping the search-service repository scan separate from any
 * future Spring Data JPA repository scan so the two never confuse Spring Boot's
 * autoconfiguration.
 *
 * <p>Index settings (custom edge_ngram analyzer for the {@code name.edge}
 * sub-field that powers autocomplete) are declared on the {@code @Setting}
 * annotation on {@code EventDocument} — Spring Data ES applies them on first
 * write if the index does not yet exist. Keeping them on the document keeps
 * mapping and settings co-located.
 */
@Configuration
@EnableElasticsearchRepositories(basePackages = "com.ticketing.search.domain.repository")
public class ElasticsearchConfig extends ElasticsearchConfiguration {

    @Value("${elasticsearch.url:http://localhost:9200}")
    private String elasticsearchUrl;

    @Override
    public ClientConfiguration clientConfiguration() {
        // Strip the scheme — ClientConfiguration.connectedTo takes host:port,
        // and useSsl()/usePlainHttp is controlled separately.
        String hostAndPort = elasticsearchUrl
                .replaceFirst("^https?://", "");

        var builder = ClientConfiguration.builder()
                .connectedTo(hostAndPort);

        if (elasticsearchUrl.startsWith("https://")) {
            builder.usingSsl();
        }

        return builder.build();
    }
}
