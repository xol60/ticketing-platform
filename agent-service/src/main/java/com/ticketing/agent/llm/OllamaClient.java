package com.ticketing.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin HTTP client for the local Ollama server.
 *
 * <p>Hand-rolled on {@link HttpClient} because Ollama publishes no Java SDK —
 * unlike the hosted providers, where writing your own client throws away
 * streaming, retries and typed errors somebody already got right.
 *
 * <p>Two calls, both blocking and both non-streaming. Streaming would buy
 * nothing here: ingestion is a batch job nobody watches, and the response is
 * parsed as a whole document anyway.
 */
@Slf4j
@Component
public class OllamaClient {

    private final AgentProperties.Ollama config;
    private final ObjectMapper mapper;
    private final HttpClient chatHttp;
    private final HttpClient embedHttp;

    public OllamaClient(AgentProperties properties) {
        this.config = properties.getOllama();
        this.mapper = new ObjectMapper();

        // Separate clients so the two call sites keep their own connect
        // timeouts. Embedding is fast and should fail fast; generation is slow
        // by nature and a short timeout there just turns a working batch into
        // a mystery.
        this.chatHttp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getLlm().getConnectTimeoutMs()))
                .build();
        this.embedHttp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getEmbedding().getConnectTimeoutMs()))
                .build();
    }

    /**
     * One constrained generation.
     *
     * <p>{@code schema} is passed as Ollama's {@code format} field, which
     * applies it during decoding rather than validating afterwards. That
     * distinction is the whole reason this is worth doing: a schema checked
     * after the fact turns malformed output into a retry, while a schema
     * enforced at decode time makes the malformed output unreachable. In
     * particular, declaring the eight dims as an {@code enum} means the model
     * is structurally incapable of inventing a ninth.
     *
     * @return the assistant's message content — a JSON document conforming to
     *         {@code schema}
     * @throws OllamaException on transport failure or a non-2xx response
     */
    public String generateJson(String systemPrompt, String userPrompt, JsonNode schema) {
        var llm = config.getLlm();

        ObjectNode options = mapper.createObjectNode();
        options.put("temperature", llm.getTemperature());
        options.put("num_ctx", llm.getNumCtx());

        ArrayNode messages = mapper.createArrayNode();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));

        ObjectNode body = mapper.createObjectNode();
        body.put("model", llm.getModel());
        body.set("messages", messages);
        body.put("stream", false);
        body.set("format", schema);
        body.set("options", options);
        // Qwen3 interleaves <think> blocks unless this is off; they break
        // schema-constrained decoding and cost tokens on a transcription task.
        body.put("think", llm.isThink());

        JsonNode response = post(chatHttp, "/api/chat", body, llm.getReadTimeoutMs());

        JsonNode content = response.path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new OllamaException("Ollama returned no message content for model "
                    + llm.getModel());
        }
        return content.asText();
    }

    /**
     * Embeds one string.
     *
     * <p>{@code prefix} carries the asymmetric-model instruction — empty for
     * bge-m3, which is trained without one. It is applied here rather than by
     * callers so a model swap cannot leave half the call sites unprefixed:
     * that failure does not throw, it just quietly makes every comparison
     * slightly wrong.
     *
     * @throws OllamaException on transport failure, or if the returned vector
     *         is not the width the schema was built for
     */
    public float[] embed(String text, String prefix) {
        var emb = config.getEmbedding();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", emb.getModel());
        body.put("input", prefix + text);

        JsonNode response = post(embedHttp, "/api/embed", body, emb.getReadTimeoutMs());

        JsonNode vectors = response.path("embeddings");
        if (!vectors.isArray() || vectors.isEmpty()) {
            throw new OllamaException("Ollama returned no embedding for model " + emb.getModel());
        }

        JsonNode first = vectors.get(0);
        if (first.size() != emb.getDimension()) {
            // Caught here rather than at the INSERT, because Postgres would
            // reject it with a message about a vector column that says nothing
            // about which model produced the wrong width.
            throw new OllamaException(String.format(
                    "Embedding model %s returned %d dimensions, schema expects %d — "
                            + "changing embedding model requires a migration and a full re-embed",
                    emb.getModel(), first.size(), emb.getDimension()));
        }

        float[] out = new float[first.size()];
        for (int i = 0; i < out.length; i++) out[i] = (float) first.get(i).asDouble();
        return out;
    }

    /** True when the server answers — used by health reporting, never to gate a call. */
    public boolean isReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getBaseUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            return embedHttp.send(request, HttpResponse.BodyHandlers.discarding())
                    .statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("Ollama not reachable at {}: {}", config.getBaseUrl(), e.getMessage());
            return false;
        }
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private JsonNode post(HttpClient http, String path, ObjectNode body, int readTimeoutMs) {
        String url = config.getBaseUrl() + path;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() / 100 != 2) {
                throw new OllamaException(String.format(
                        "Ollama %s returned %d: %s", path, response.statusCode(),
                        abbreviate(response.body())));
            }
            return mapper.readTree(response.body());

        } catch (OllamaException e) {
            throw e;
        } catch (InterruptedException e) {
            // Restore the flag before wrapping: swallowing it here would leave
            // the Kafka consumer thread unable to notice a shutdown request.
            Thread.currentThread().interrupt();
            throw new OllamaException("Interrupted calling Ollama " + path, e);
        } catch (Exception e) {
            throw new OllamaException("Failed calling Ollama " + path + " at " + url, e);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    /** Transport or protocol failure talking to Ollama. */
    public static class OllamaException extends RuntimeException {
        public OllamaException(String message) { super(message); }
        public OllamaException(String message, Throwable cause) { super(message, cause); }
    }
}
