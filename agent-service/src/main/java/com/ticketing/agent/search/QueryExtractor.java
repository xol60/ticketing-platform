package com.ticketing.agent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.agent.config.AgentProperties;
import com.ticketing.agent.domain.repository.TagRepository;
import com.ticketing.agent.vector.EmbeddingService;
import com.ticketing.agent.llm.OllamaClient;
import com.ticketing.agent.validation.TextNormalizer;
import com.ticketing.agent.vector.TagCatalog;
import com.ticketing.common.agent.Taxonomy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

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
    private final TagCatalog       tagCatalog;
    private final TagRepository    tagRepository;
    private final EmbeddingService embeddings;
    private final AgentProperties  properties;
    private final ObjectMapper   mapper = new ObjectMapper();

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

            3. THINGS THEY DO NOT WANT — write what they ruled out in "excludeText",
               in their own words, as a short positive noun phrase. One entry per
               separate thing. Do NOT name a tag, a slug or a category: you are not
               shown the catalogue, and you do not need to be — the phrase is matched
               against it afterwards.
                 "an evening out, not sports"      -> excludeText: ["sports"]
                 "a concert but not too crowded"   -> excludeText: ["too crowded"]
                 "live music, nothing electronic"  -> excludeText: ["electronic music"]
                 "something in london, not a conference"
                                                   -> excludeText: ["a conference"]

               NEVER write a negative phrase into a vibe facet. "not too crowded" and
               "crowded" are near-identical to a vector, so a negation left in the vibe
               returns exactly what the person ruled out. It belongs here instead, and
               here it is stated positively — "too crowded", not "not too crowded".

               Empty unless the sentence actually rules something out. A request that
               only says what it wants rules out nothing.

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
                excludeText:     ["too crowded"]

            Note what happened: "new york" and "$80" left the vibe entirely, "not too
            crowded" became an exclusion rather than a facet, and "thing" was dropped.

            Emit nothing you were not told. An empty list is the right answer when the
            person said nothing on that axis.
            """;

    public QueryExtraction extract(String message) {
        if (message == null || message.isBlank()) return QueryExtraction.empty();

        try {
            // No tag catalogue. The prompt is now a constant, whatever the
            // vocabulary grows to.
            String raw = ollama.generateJson(SYSTEM_PROMPT,
                    Taxonomy.promptBlock() + "\nREQUEST:\n" + message,
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

        // Free text, deliberately. An enum of slugs would have to be rendered
        // into the prompt for the model to choose from — 3,135 characters at
        // eighteen tags, linear in the vocabulary, and measured to cost 3
        // points of retrieval because the model copies slugs into facet values
        // once it has seen them. A phrase is resolved against the catalogue
        // afterwards, by vector, which costs nothing per tag.
        ObjectNode excludes = mapper.createObjectNode();
        excludes.put("type", "array");
        excludes.set("items", str("A thing the person ruled out, in their words, "
                + "stated positively. Not a slug."));

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
        props.set("excludeText", excludes);

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        // Every field is required, including the nullable ones. Constrained
        // decoding treats an optional field as one it may simply omit, and
        // `ordinal` was being dropped on every single turn — "the second one"
        // arrived with no ordinal at all, so a selection was indistinguishable
        // from a search. Requiring the field forces an explicit null instead.
        schema.putArray("required")
                .add("vibeFacets").add("excludeText").add("clearFields")
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

    /**
     * Share of the catalogue above which an exclusion list is an enumeration
     * rather than a reading.
     *
     * <p>A fraction, not a count, and the difference is not cosmetic. The rule
     * was written as "at most 2" against a ten-tag vocabulary, where two was a
     * fifth of everything on offer. The vocabulary is now eighteen tags, and
     * the same constant made an ordinary request impossible: "an evening out,
     * not sports" has to rule out {@code team-sport-fixture},
     * {@code motorsport-race} and {@code combat-sport} — three of eighteen, a
     * sixth of the catalogue and plainly a reading of the sentence — and the
     * cap discarded all three, so the search returned a Super Bowl, a Champions
     * League final and a baseball game.
     *
     * <p>Splitting one {@code sports} tag into three was right for tagging and
     * silently broke negation, because the two decisions were coupled through
     * an absolute number that nobody thought to revisit. A share cannot come
     * uncoupled that way.
     *
     * <p>A third is where the original evidence sits: the list that motivated
     * this gate excluded ten tags out of ten, and the next worst excluded six
     * of ten. Both are far above a third; every genuine reading measured has
     * been at or below a sixth.
     */
    private static final double MAX_EXCLUSION_SHARE = 1.0 / 3;

    /** Never below this, so a vocabulary too small to take a share still works. */
    private static final int MIN_EXCLUSION_ALLOWANCE = 2;

    /**
     * Turns what the person ruled out into tag slugs the filter can apply.
     *
     * <h3>Why the model is not asked for a slug</h3>
     * It used to be, with the catalogue rendered into the prompt so it had
     * something to choose from. That cost 3,135 characters at eighteen tags and
     * grew with every tag added; at a hundred it would have dominated the
     * request. Worse, it changed the rest of the extraction: shown eighteen
     * definitions, the model started writing slugs into facet values —
     * "basketball game" came back as {@code format: "team-sport-fixture"} — and
     * a slug embeds nothing like the prose a facet is quoted from. Removing the
     * catalogue was measured at +3 points across the evaluation set, with the
     * whole difference in the groups the vector path serves.
     *
     * <p>A phrase is resolved here instead, against the same vectors the rest
     * of the system uses. The prompt is now constant whatever the vocabulary
     * grows to — the property the ingest side has always had.
     *
     * <h3>Three gates, each decidable</h3>
     * <ol>
     *   <li>No negation marker in the sentence, no exclusion. Required because
     *       the schema makes the model produce the array whether or not the
     *       request rules anything out — "tech conference", "basketball game"
     *       and "soccer match" all came back excluding things, the model
     *       listing what the event is not.</li>
     *   <li>More than {@code maxExcludePhrases} separate things, and it is
     *       listing rather than reading.</li>
     *   <li>The nearest tag must stand clear of the runner-up. See
     *       {@code excludeGapMin} — this is what stops "nothing electronic"
     *       from deleting live music, and it is a comparison rather than a
     *       floor because absolute similarity puts that mistake <em>above</em>
     *       a correct exclusion.</li>
     * </ol>
     */
    private QueryExtraction groundExclusions(QueryExtraction q, String message) {
        if (q.excludeTags().isEmpty()) return q;

        if (!NEGATION.matcher(message).find()) {
            log.debug("Dropping excludeText {} — the request rules nothing out: \"{}\"",
                    q.excludeTags(), message);
            return withExclusions(q, List.of());
        }
        if (q.excludeTags().size() > properties.getValidation().getMaxExcludePhrases()) {
            log.debug("Dropping excludeText {} — {} separate things is an enumeration, "
                    + "not a reading: \"{}\"", q.excludeTags(), q.excludeTags().size(), message);
            return withExclusions(q, List.of());
        }

        double gapMin = properties.getValidation().getExcludeGapMin();
        List<String> slugs = new ArrayList<>(q.excludeTags().size());
        for (String phrase : q.excludeTags()) {
            List<Object[]> near;
            try {
                near = tagRepository.nearestTwo(embeddings.embedQuery(phrase));
            } catch (Exception e) {
                // One phrase failing to embed costs that phrase, not the search.
                log.warn("Could not resolve exclusion '{}': {}", phrase, e.getMessage());
                continue;
            }
            if (near.isEmpty()) continue;

            String slug = (String) near.get(0)[0];
            double best = ((Number) near.get(0)[1]).doubleValue();
            double gap  = near.size() < 2 ? best
                    : best - ((Number) near.get(1)[1]).doubleValue();

            if (gap < gapMin) {
                log.debug("Not excluding '{}' — nearest is {} at {} but {} is {} behind, "
                        + "so no tag in the catalogue means this", phrase, slug,
                        String.format("%.3f", best), near.get(1)[0], String.format("%.3f", gap));
                continue;
            }
            log.debug("Excluding '{}' -> {} ({}, clear of the next by {})", phrase, slug,
                    String.format("%.3f", best), String.format("%.3f", gap));
            slugs.add(slug);
        }
        return withExclusions(q, slugs);
    }

    private static QueryExtraction withExclusions(QueryExtraction q, List<String> excludes) {
        return new QueryExtraction(q.intent(), q.ordinal(), q.clearFields(), q.properNoun(),
                q.city(), q.dateExpression(), q.priceMax(), q.vibeFacets(), excludes);
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
        root.path("excludeText").forEach(n -> {
            String phrase = n.asText("").trim();
            if (!phrase.isEmpty()) excludes.add(phrase);
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
