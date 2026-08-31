package com.ticketing.agent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.agent.llm.OllamaClient;
import com.ticketing.agent.vector.TagCatalog;
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
    // The vocabulary comes from the database, not from Taxonomy. A tag a
    // reviewer created must be excludable the moment it exists; see TagCatalog.
    private final TagCatalog   tagCatalog;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You split a person's request for something to do into structured parts.
            You do not answer it, and you do not invent anything they did not say.

            FIRST, WHAT IS THIS PERSON DOING?
            Set "intent" to exactly one of:
              FIND    — looking for something. Anything describing what they want.
                        "a musical in london", "something calm", "taylor swift"
              SELECT  — pointing at a row already shown.
                        "the second one", "that first one", "cái thứ 2"
              DETAIL  — asking about the event they already chose. Questions about
                        one thing, not a search for another.
                        "what time does it start", "how much is it", "where is it"
              COMPARE — asking how the shown results differ.
                        "what's the difference", "which is cheaper"
              HANDOFF — ready to buy the one they chose.
                        "book it", "I'll take that one", "let's get tickets"

            For SELECT, DETAIL and COMPARE, leave vibeFacets EMPTY. A question is
            not a description. "what time does it start" is DETAIL with no facets
            — reading "start time" as a duration facet turns a question about the
            chosen event into a search that loses it.

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

               A CITY IS NOT A NAME for this purpose. "in london" fills "city"
               and leaves properNoun null — putting it in both makes the search
               look for a venue called London and find nothing.
                 "a musical in london"       -> properNoun: null, city: "london"

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

            POINTING AT A PREVIOUS RESULT
            "the second one", "that first one", "cái thứ 2" -> ordinal: 2, 1, 2.
            Set ordinal and leave everything else null — the person is selecting,
            not searching. Do not guess which event they meant; the position is
            all you need to report.

            REMOVING A CONSTRAINT
            clearFields is almost always empty. It is ONLY for a person taking
            something back in words:
              "forget the budget"    -> ["priceMax"]
              "anywhere is fine"     -> ["city"]
              "any date works"       -> ["dateExpression"]

            Not mentioning a slot is not removing it. These all give []:
              "the second one"       -> []   (a selection, nothing was retracted)
              "a musical in london"  -> []   (naming a city is not clearing a date)
              "something cheaper"    -> []   (that is a new price, not a removal)

            If you cannot quote the words that took something back, the answer
            is [].

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
                    Taxonomy.promptBlock() + "\n" + tagCatalog.promptBlock()
                            + "\nREQUEST:\n" + message,
                    schema());
            return groundExclusions(parse(raw), message);
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
        tagCatalog.slugs().forEach(slugs::add);
        ObjectNode excludes = mapper.createObjectNode();
        excludes.put("type", "array");
        excludes.set("items", tagEnum);

        ObjectNode props = mapper.createObjectNode();
        ObjectNode intentEnum = mapper.createObjectNode();
        intentEnum.put("type", "string");
        intentEnum.putArray("enum")
                .add("FIND").add("SELECT").add("DETAIL").add("COMPARE").add("HANDOFF");
        props.set("intent", intentEnum);

        ObjectNode clearEnum = mapper.createObjectNode();
        clearEnum.put("type", "string");
        clearEnum.putArray("enum").add("city").add("dateExpression").add("priceMax").add("excludeTags");
        ObjectNode clears = mapper.createObjectNode();
        clears.put("type", "array");
        clears.set("items", clearEnum);
        props.set("clearFields", clears);
        props.set("ordinal", nullableNumber(
                "1-based position the person pointed at in the previous results, or null."));
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
        // Every field is required, including the nullable ones. Constrained
        // decoding treats an optional field as one it may simply omit, and
        // `ordinal` was being dropped on every single turn — "the second one"
        // arrived with no ordinal at all, so a selection was indistinguishable
        // from a search. Requiring the field forces an explicit null instead.
        schema.putArray("required")
                .add("vibeFacets").add("excludeTags").add("clearFields")
                .add("intent").add("ordinal").add("properNoun").add("city")
                .add("dateExpression").add("priceMax");
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

    /**
     * Negation markers. An exclusion is a claim that the person ruled something
     * out, and a sentence that rules nothing out cannot support one.
     */
    private static final java.util.regex.Pattern NEGATION = java.util.regex.Pattern.compile(
            "\\b(not|no|nothing|none|without|except|avoid|apart from|other than|"
          + "kh\u00f4ng|ch\u1eb3ng|\u0111\u1eebng|tr\u1eeb|ngo\u1ea1i tr\u1eeb)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Above this many exclusions the list is an enumeration, not a reading. */
    private static final int MAX_EXCLUSIONS = 2;

    /**
     * Holds {@code excludeTags} to what the sentence actually rules out.
     *
     * <h3>Why this is needed at all</h3>
     * {@code excludeTags} is required by the JSON schema, so the grammar makes
     * the model produce an array whether or not the request rules anything out
     * — the same failure {@code clearFields} had. And unlike every other model
     * output in this service, an exclusion is a <em>hard filter</em>: a facet
     * must quote its source, a tag assignment must survive review, an exclusion
     * deletes events on the model's word alone.
     *
     * <h3>What was measured</h3>
     * Across the 57-query evaluation set, ten requests produced exclusions and
     * the distribution was bimodal with nothing in between:
     * <ul>
     *   <li>Three excluded exactly one tag, and all three were right —
     *       "not too crowded" ruled out large-scale, "not sports" ruled out
     *       sports.</li>
     *   <li>Seven excluded five to ten of the ten tags in the vocabulary, and
     *       all seven were wrong. "live music, nothing electronic" excluded all
     *       ten <em>including live-music</em>, and "theatre but not a musical"
     *       excluded performing-arts. Each destroyed its own request, cutting
     *       the candidate set from 64 events to 6.</li>
     *   <li>Three of the seven — "tech conference", "basketball game",
     *       "soccer match" — contained no negation at all. The model was
     *       listing what the event is not.</li>
     * </ul>
     *
     * <h3>Two gates, both decidable</h3>
     * A sentence with no negation marker supports no exclusion. And a list
     * longer than {@link #MAX_EXCLUSIONS} is discarded whole rather than
     * trimmed: once the model has enumerated the vocabulary, no subset of that
     * enumeration is a reading of the sentence, so keeping two of ten would
     * just be choosing arbitrarily which correct answers to delete.
     */
    private QueryExtraction groundExclusions(QueryExtraction q, String message) {
        if (q.excludeTags().isEmpty()) return q;

        String reason = null;
        if (!NEGATION.matcher(message).find())            reason = "the request rules nothing out";
        else if (q.excludeTags().size() > MAX_EXCLUSIONS) reason = "the list enumerates the vocabulary";
        if (reason == null) return q;

        log.debug("Dropping excludeTags {} — {}: \"{}\"", q.excludeTags(), reason, message);
        return new QueryExtraction(q.intent(), q.ordinal(), q.clearFields(), q.properNoun(),
                q.city(), q.dateExpression(), q.priceMax(), q.vibeFacets(), List.of());
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
            if (tagCatalog.isKnown(slug)) excludes.add(slug);
        });

        JsonNode price = root.path("priceMax");
        JsonNode city  = root.path("city");
        JsonNode date  = root.path("dateExpression");
        JsonNode noun  = root.path("properNoun");
        JsonNode ord   = root.path("ordinal");

        QueryExtraction.Intent intent;
        try {
            intent = QueryExtraction.Intent.valueOf(root.path("intent").asText("FIND"));
        } catch (IllegalArgumentException e) {
            intent = QueryExtraction.Intent.FIND;
        }

        List<String> clears = new ArrayList<>();
        root.path("clearFields").forEach(n -> {
            String f = n.asText("").trim();
            if (!f.isEmpty()) clears.add(f);
        });

        // The one place the model's reading of a request is visible. Without it a
        // bad result is indistinguishable from a bad extraction: the search
        // looks correct on every filter it was given, and nobody can see that
        // it was given the wrong ones.
        if (log.isDebugEnabled()) {
            // clearFields belongs here as much as the values do. Without it the
            // line showed "city=tokyo" on a turn whose city was then wiped by a
            // clearFields the log never mentioned, which made a real bug look
            // like the extraction had worked.
            log.debug("Extracted intent={} noun={} city={} date={} priceMax={} facets={} exclude={} clear={}",
                    intent, noun.asText(null), city.asText(null), date.asText(null),
                    price.isNumber() ? price.asDouble() : null,
                    facets.stream().map(f -> f.dim() + ":" + f.value()).toList(), excludes, clears);
        }

        return new QueryExtraction(
                intent,
                ord.isNull() || ord.isMissingNode() || !ord.isNumber() ? null : ord.asInt(),
                clears,
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
