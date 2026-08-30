package com.ticketing.agent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.agent.llm.OllamaClient;
import com.ticketing.common.agent.Taxonomy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The hot-path LLM call: one message in, a split query out.
 *
 * <p>The narrowest of the three call sites. It classifies phrases into slots
 * and does not reason, which is why it runs at the lowest effort — splitting a
 * sentence does not improve with deliberation.
 *
 * <h3>Failure is not an error here</h3>
 * If extraction fails, the caller searches with whatever it already has rather
 * than showing the user a failure. A search with no vibe still returns events
 * ranked by popularity and proximity, which is a worse answer but an answer;
 * an error message is not.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryExtractor {

    private final OllamaClient ollama;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You split a person's request for something to do into structured parts.
            You do not answer it, and you do not invent anything they did not say.

            FOUR KINDS OF INFORMATION, AND THEY GO TO DIFFERENT PLACES

            0. A NAME — an artist, band, team, show or venue the person named.
               Put it in "properNoun", spelled as they wrote it, and do NOT also
               describe it in a vibe facet. A name is looked up literally; turned
               into a vibe phrase it becomes a mood search that finds everything
               except what was asked for.
                 "taylor swift"              -> properNoun: "taylor swift"
                 "anything at wembley"       -> properNoun: "wembley"
                 "something like coldplay"   -> properNoun: null   (a comparison,
                                                not a request for Coldplay)

            1. EXACT CONSTRAINTS — city, date, maximum price.
               Put these in their own fields. Never repeat them inside a vibe facet.
               A city named inside a vibe facet matches events in other cities whose
               description happens to mention that city.

            2. VIBE — what kind of experience they want.
               Split into facets under the dimension each belongs to. Write each value
               as a short plain phrase, positively stated.

            3. THINGS THEY DO NOT WANT — put the matching catalogue slug in "excludeTags".
               NEVER write a negative phrase into a vibe facet. "not too crowded" and
               "crowded" are near-identical to a vector, so a negation left in the vibe
               returns exactly what the person ruled out.
                 "not too crowded"      -> excludeTags: ["large-scale"]
                 "nothing late"         -> excludeTags: ["late-night"]
                 "no big stadium shows" -> excludeTags: ["large-scale"]

            DATES
            Return the person's own words in "dateExpression" — "this weekend", "next
            month", "in february", "tonight". Never compute a date. Leave it null if
            they did not mention time.

            DROP FILLER
            "looking for", "can you help me find", "I want", "something" carry no
            meaning and dilute the vibe. Extract the content, not the request.

            WORKED EXAMPLE
              "chill jazz thing saturday night in new york, not too crowded, under $80"
                city:            "new york"
                dateExpression:  "saturday night"
                priceMax:        80
                vibeFacets:      [{"dim":"atmosphere","value":"calm, low-key, unhurried"},
                                  {"dim":"format","value":"live jazz performance"}]
                excludeTags:     ["large-scale"]

            Note what happened: "new york" and "$80" left the vibe entirely, "not too
            crowded" became an exclusion rather than a facet, and "thing" was dropped.

            Emit nothing you were not told. An empty list is the right answer when the
            person said nothing on that axis.
            """;

    public QueryExtraction extract(String message) {
        if (message == null || message.isBlank()) return QueryExtraction.empty();

        try {
            String raw = ollama.generateJson(SYSTEM_PROMPT,
                    Taxonomy.promptBlock() + "\nREQUEST:\n" + message,
                    schema());
            return parse(raw);
        } catch (Exception e) {
            // Never surfaced to the user. Searching with no vibe returns a
            // worse answer; failing returns none.
            log.warn("Query extraction failed, falling back to an unconstrained search: {}",
                    e.getMessage());
            return QueryExtraction.empty();
        }
    }

    private JsonNode schema() {
        ObjectNode dimEnum = mapper.createObjectNode();
        dimEnum.put("type", "string");
        ArrayNode dims = dimEnum.putArray("enum");
        Taxonomy.DIMS.forEach(d -> dims.add(d.name()));

        ObjectNode facetProps = mapper.createObjectNode();
        facetProps.set("dim", dimEnum);
        facetProps.set("value", str("Short positive phrase. No city, date, price or negation."));
        ObjectNode facetItem = mapper.createObjectNode();
        facetItem.put("type", "object");
        facetItem.set("properties", facetProps);
        facetItem.putArray("required").add("dim").add("value");

        ObjectNode facets = mapper.createObjectNode();
        facets.put("type", "array");
        facets.set("items", facetItem);

        // Constrained to the catalogue, so an exclusion can only ever name a
        // tag the filter can actually apply.
        ObjectNode tagEnum = mapper.createObjectNode();
        tagEnum.put("type", "string");
        ArrayNode slugs = tagEnum.putArray("enum");
        Taxonomy.TAGS.forEach(t -> slugs.add(t.slug()));
        ObjectNode excludes = mapper.createObjectNode();
        excludes.put("type", "array");
        excludes.set("items", tagEnum);

        ObjectNode props = mapper.createObjectNode();
        props.set("properNoun", nullableStr(
                "Artist, band, team, show or venue named by the person, or null."));
        props.set("city", nullableStr("City exactly as the person wrote it, or null."));
        props.set("dateExpression", nullableStr("Their own words for when, or null. Never a computed date."));
        props.set("priceMax", nullableNumber("Maximum price as a number, or null."));
        props.set("vibeFacets", facets);
        props.set("excludeTags", excludes);

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        schema.putArray("required").add("vibeFacets").add("excludeTags");
        return schema;
    }

    private ObjectNode str(String description) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "string");
        n.put("description", description);
        return n;
    }

    /**
     * A nullable field is expressed as {@code ["string","null"]} rather than
     * omitted from {@code required}. Constrained decoding then forces the model
     * to emit an explicit null instead of silently dropping the key, which is
     * the difference between "they said nothing about price" and "the model
     * forgot to mention price".
     */
    private ObjectNode nullableStr(String description) {
        ObjectNode n = mapper.createObjectNode();
        n.putArray("type").add("string").add("null");
        n.put("description", description);
        return n;
    }

    private ObjectNode nullableNumber(String description) {
        ObjectNode n = mapper.createObjectNode();
        n.putArray("type").add("number").add("null");
        n.put("description", description);
        return n;
    }

    private QueryExtraction parse(String raw) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = mapper.readTree(raw);

        List<FacetQuery> facets = new ArrayList<>();
        root.path("vibeFacets").forEach(n -> {
            String dim = n.path("dim").asText(null);
            String value = n.path("value").asText("").trim();
            if (Taxonomy.isKnownDim(dim) && !value.isEmpty()) {
                facets.add(new FacetQuery(dim, value));
            }
        });

        List<String> excludes = new ArrayList<>();
        root.path("excludeTags").forEach(n -> {
            String slug = n.asText("").trim();
            if (Taxonomy.isKnownTag(slug)) excludes.add(slug);
        });

        JsonNode price = root.path("priceMax");
        JsonNode city  = root.path("city");
        JsonNode date  = root.path("dateExpression");
        JsonNode noun  = root.path("properNoun");

        return new QueryExtraction(
                noun.isNull()  || noun.isMissingNode()  ? null : blankToNull(noun.asText()),
                city.isNull()  || city.isMissingNode()  ? null : blankToNull(city.asText()),
                date.isNull()  || date.isMissingNode()  ? null : blankToNull(date.asText()),
                price.isNull() || price.isMissingNode() || !price.isNumber()
                        ? null : BigDecimal.valueOf(price.asDouble()),
                facets, excludes);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
