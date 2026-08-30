package com.ticketing.agent.vector;

import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.llm.OllamaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Turns text into the pgvector literal the repositories bind.
 *
 * <h3>Why a literal and not a float array</h3>
 * Every vector operation in this service happens inside Postgres: cosine
 * distance with {@code <=>}, comparison against approved facets, nearest-tag
 * lookup. The JVM never needs a vector for anything except handing it to the
 * database, so it is formatted once here and passed as text with an explicit
 * {@code CAST(? AS vector)}. Mapping a JDBC vector type would add a dependency
 * and ship 1024 floats into Java on every read to do nothing with them.
 *
 * <h3>Document and query are not the same call</h3>
 * The two entry points exist because asymmetric embedding models encode a
 * short query and a long passage differently, and mixing them systematically
 * under-scores the query side. bge-m3 needs no prefix and both are empty, but
 * routing through separate methods means swapping to a model that does need
 * them (nomic-embed-text wants {@code search_query:} / {@code search_document:})
 * is a config change rather than a hunt for unprefixed call sites — a failure
 * that never throws and just makes every comparison slightly wrong.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final OllamaClient ollama;
    private final AgentProperties properties;

    /** Embeds stored content — a facet value or a tag description. */
    public String embedDocument(String text) {
        return toVectorLiteral(
                ollama.embed(text, properties.getOllama().getEmbedding().getDocumentPrefix()));
    }

    /** Embeds a user-side phrase, on the hot path. */
    public String embedQuery(String text) {
        return toVectorLiteral(
                ollama.embed(text, properties.getOllama().getEmbedding().getQueryPrefix()));
    }

    /** The model version stamped on every row this service writes, for re-embed cutover. */
    public String modelVersion() {
        return properties.getOllama().getEmbedding().getModel();
    }

    /**
     * Formats as {@code [0.013,-0.28,...]} — pgvector's text input form.
     *
     * <p>{@link Locale#ROOT} is not optional. Under a locale that uses a comma
     * as the decimal separator, {@code %f} would emit {@code 0,013} and every
     * vector would silently become garbage with the right number of elements —
     * Postgres would parse it as extra dimensions and reject it with a width
     * error that points nowhere near the cause.
     */
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        return sb.append(']').toString();
    }
}
