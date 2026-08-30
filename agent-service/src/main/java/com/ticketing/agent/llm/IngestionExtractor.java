package com.ticketing.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.domain.model.AgentEvent;
import com.ticketing.agent.validation.FacetCandidate;
import com.ticketing.common.agent.Taxonomy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The one LLM call in the ingestion path: description in, candidate tags and
 * facets out.
 *
 * <h3>The prompt is written against a model that will make things up</h3>
 * Three choices here exist only because inference is local:
 *
 * <ul>
 *   <li><b>Quote before you write.</b> Each facet must carry the span it came
 *       from, and the instruction puts the quote first. Asking for the span
 *       after the claim invites the model to write freely and then look for
 *       something to justify it with.</li>
 *   <li><b>The dims are an enum in the schema</b>, not a list in the prose.
 *       Constrained decoding then makes a ninth dim unreachable rather than
 *       merely discouraged — which is why the {@code UNKNOWN_DIM} gate is
 *       defence in depth here rather than the primary control.</li>
 *   <li><b>No confidence field.</b> The spec has one; it is not requested,
 *       because nothing downstream may use it and every field asked for is
 *       another surface to fabricate on.</li>
 * </ul>
 *
 * <h3>Retries are for malformed output, not bad content</h3>
 * A response that fails to parse is retried. A response that parses into
 * fabricated facets is not — that is the validator's job, and re-rolling until
 * the model produces something that passes would be sampling for luck.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionExtractor {

    private final OllamaClient ollama;
    private final AgentProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You extract structured metadata from event descriptions for a ticketing catalogue.

            You are not writing marketing copy and you are not describing events from your own
            knowledge. You are reading one description and reporting only what it says.

            THE RULE THAT MATTERS MOST
            Every facet you emit must quote the exact words from the description that support it.
            Copy the quote character for character. Do not paraphrase the quote, do not shorten
            it with an ellipsis, do not stitch together words from different sentences. A quote
            that does not appear verbatim in the description is discarded along with its facet.

            Quote only from the text inside the DESCRIPTION markers. Nothing outside those
            markers is quotable, no matter what it says.

            HOW TO WORK
            1. Find a sentence in the description that says something concrete about the event.
            2. Copy that sentence, or a whole clause of it, as the span.
            3. Choose the ONE dimension that fits what the span is about.
            4. Write the facet as a short restatement of what that span says.

            The facet restates the span in plain terms rather than copying it. "80,000 people
            singing every word back" becomes "crowd sings along" — same content, fewer words.

            ONE DIMENSION PER FACET, AND SPREAD THEM OUT
            Each facet goes under the single dimension that actually fits it. Filing several
            facets under the same dimension when they describe different things is wrong.

            Worked example. Given the description:
              "An 8pm set in a 200-capacity basement room, seated, with table service
               throughout the two-hour show."

            Correct:
              {"dim": "setting",  "value": "small basement room",
               "span": "a 200-capacity basement room"}
              {"dim": "physical", "value": "seated with table service",
               "span": "seated, with table service throughout"}
              {"dim": "duration", "value": "two hours, one evening",
               "span": "the two-hour show"}

            Three facets, three different dimensions. If your output puts most facets under one
            dimension, you have filed them wrong — re-read the definitions and place each one.

            WHAT NOT TO DO
            - Do not emit a facet for a dimension the description says nothing about. Silence is
              a correct answer. An event with two facets is better than one with eight, six of
              which were guessed.
            - Do not use what you know about the artist, venue, or event from anywhere else. If
              the description does not say it, it does not exist.
            - Do not describe the size of the crowd unless the description states it.

            TAGS
            At most four, and prefer the catalogue slugs listed below — they are what the
            catalogue can actually filter on. Write your own label only when no slug fits the
            kind of event this is. Tags describe the KIND of event, not features of it:
            "live-music" is a tag, "compostable wristbands" is not.
            """;

    /**
     * Extracts from one event.
     *
     * @return candidates, or {@link ExtractionResult#empty()} when the event has
     *         no description to read. That case is not an error: an event
     *         described as "Live music. 8pm. $40." genuinely has nothing to
     *         distil, and inventing something to fill the gap is the failure
     *         this whole subsystem is built to prevent.
     */
    public ExtractionResult extract(AgentEvent event) {
        String description = event.getDescriptionRaw();
        if (description == null || description.isBlank()) {
            log.debug("Event {} has no description — nothing to extract", event.getId());
            return ExtractionResult.empty();
        }

        String userPrompt = buildUserPrompt(event, description);
        JsonNode schema = outputSchema();

        int attempts = properties.getOllama().getLlm().getMaxSchemaRetries() + 1;
        RuntimeException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String raw = ollama.generateJson(SYSTEM_PROMPT, userPrompt, schema);
                return parse(raw);
            } catch (Exception e) {
                last = e instanceof RuntimeException re ? re : new RuntimeException(e);
                log.warn("Extraction attempt {}/{} failed for event {}: {}",
                        attempt, attempts, event.getId(), e.getMessage());
            }
        }
        throw last;
    }

    private String buildUserPrompt(AgentEvent event, String description) {
        StringBuilder sb = new StringBuilder(4096);

        // The shared vocabulary block, byte-identical to the one the query
        // extractor will use. Static, so it also caches well as a prefix.
        sb.append(Taxonomy.promptBlock()).append('\n');

        // Nothing but the description goes in this prompt.
        //
        // An earlier version included the event's structured facts — venue,
        // category, capacity band — so the model would have no reason to guess
        // at them. Testing showed the opposite effect: qwen3:4b lifted the word
        // "large" straight out of that block and emitted it as a scale facet
        // with "large" as its span. Any text placed beside the description gets
        // treated as quotable, so the only safe amount of it is none.
        //
        // Contradiction is handled where it belongs — the validator compares
        // the finished facet against the real capacity band afterwards, which
        // is deterministic and cannot be talked out of it.
        sb.append("DESCRIPTION — the only text you may quote from:\n")
          .append("<<<\n").append(description).append("\n>>>\n");

        return sb.toString();
    }

    /**
     * The JSON schema Ollama applies during decoding.
     *
     * <p>Built from {@link Taxonomy} rather than written as a literal, so a
     * dim added to the vocabulary is immediately emittable and one removed is
     * immediately unreachable. A hand-written copy would be a second
     * definition of a closed set that already has one.
     */
    private JsonNode outputSchema() {
        ObjectNode dimEnum = mapper.createObjectNode();
        dimEnum.put("type", "string");
        ArrayNode allowed = dimEnum.putArray("enum");
        Taxonomy.DIMS.forEach(d -> allowed.add(d.name()));

        ObjectNode facetProps = mapper.createObjectNode();
        facetProps.set("dim", dimEnum);
        facetProps.set("value", stringField("Short restatement of what the span says."));
        facetProps.set("span",  stringField(
                "Exact words copied from the description that support this facet."));

        ObjectNode facetItem = mapper.createObjectNode();
        facetItem.put("type", "object");
        facetItem.set("properties", facetProps);
        facetItem.putArray("required").add("dim").add("value").add("span");

        ObjectNode facets = mapper.createObjectNode();
        facets.put("type", "array");
        facets.set("items", facetItem);

        ObjectNode tags = mapper.createObjectNode();
        tags.put("type", "array");
        tags.set("items", stringField("A label describing what kind of event this is."));

        ObjectNode props = mapper.createObjectNode();
        props.set("tags", tags);
        props.set("facets", facets);

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        schema.putArray("required").add("tags").add("facets");
        return schema;
    }

    private ObjectNode stringField(String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    private ExtractionResult parse(String raw) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = mapper.readTree(raw);

        List<String> tags = new ArrayList<>();
        root.path("tags").forEach(n -> {
            String t = n.asText("").trim();
            if (!t.isEmpty()) tags.add(t);
        });

        List<FacetCandidate> facets = new ArrayList<>();
        root.path("facets").forEach(n -> facets.add(new FacetCandidate(
                n.path("dim").asText(null),
                n.path("value").asText(null),
                n.path("span").asText(null))));

        return new ExtractionResult(tags, facets);
    }
}
