package com.ticketing.common.agent;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Closed vocabularies for the event recommendation agent: 15 tags and 8 facet
 * dimensions.
 *
 * <h3>Why this lives in common-lib and not in agent-service</h3>
 * Two prompts consume this vocabulary, and they run in different code paths:
 *
 * <ul>
 *   <li><b>Ingestion</b> (offline, once per event) — turns an event description
 *       into tags + facets. Writes the {@code dim} labels.</li>
 *   <li><b>Extract</b> (hot path, every conversation turn) — turns a user
 *       message into query facets. Reads against the same {@code dim} labels.</li>
 * </ul>
 *
 * Both sides must agree on what {@code atmosphere} means, down to the wording.
 * If the two descriptions drift apart, ingestion starts writing one thing into
 * a dim while extract queries for another — and cosine similarity degrades
 * silently, with no error and no failing test. That is the quietest way to
 * break this whole subsystem, so both prompts inject {@link #promptBlock()}
 * verbatim rather than restating the vocabulary in prose.
 *
 * <h3>Tags are a filter, facets are the ranking signal</h3>
 * A tag is a hard boolean: an event either carries {@code live-music} or it
 * doesn't, and the query either excludes {@code large-scale} or it doesn't.
 * Tags never enter a vector — they resolve to {@code tag_id} in SQL.
 *
 * A facet is free text under a constrained label, embedded and compared by
 * cosine within the same dim. The dim vocabulary is closed; the <em>number</em>
 * of facets per event is not — an event may carry zero facets on a dim it
 * genuinely says nothing about. Leaving a dim empty is correct behaviour, not
 * a gap to fill; a facet invented to satisfy a template produces a vector that
 * matches queries the event cannot serve.
 *
 * <h3>Descriptions do double duty</h3>
 * Each tag's {@code description} is also the bootstrap embedding source on day
 * zero, before any event has been human-approved and kNN voting can take over
 * (the {@code tag.vector_source} column records which of the two produced the
 * stored vector). So descriptions are written as descriptive English prose,
 * not as terse labels — they have to survive being embedded on their own.
 *
 * <p>Event content is English, so this vocabulary is English.
 */
public final class Taxonomy {

    private Taxonomy() {}

    // ── Tag slugs — matchable by dim (12) ────────────────────────────────────
    public static final String TAG_LIVE_MUSIC      = "live-music";
    public static final String TAG_PERFORMING_ARTS = "performing-arts";
    public static final String TAG_SPORTS          = "sports";
    public static final String TAG_CONFERENCE_TECH = "conference-tech";
    public static final String TAG_FAMILY_KIDS     = "family-kids";

    // ── Tag slugs — attribute (4) ────────────────────────────────────────────
    public static final String TAG_HEADLINER   = "headliner";
    public static final String TAG_LARGE_SCALE = "large-scale";
    public static final String TAG_LATE_NIGHT  = "late-night";

    // ── Dim names (8) ────────────────────────────────────────────────────────
    public static final String DIM_FORMAT        = "format";
    public static final String DIM_ATMOSPHERE    = "atmosphere";
    public static final String DIM_PHYSICAL      = "physical";
    public static final String DIM_DURATION      = "duration";
    public static final String DIM_AUDIENCE      = "audience";
    public static final String DIM_SCALE         = "scale";
    public static final String DIM_PARTICIPATION = "participation";
    public static final String DIM_SETTING       = "setting";

    /**
     * @param dim      which of the eight dimensions this tag answers, or null.
     *                 A facet is only ever compared against tags on its own dim
     *                 — without that, everything competes with everything, and
     *                 a phrase about room size matches a tag about music.
     *
     *                 <p>Null means the tag is reachable by exclusion only.
     *                 {@code headliner} describes an artist's fame and
     *                 {@code late-night} a start time; neither is a dimension of
     *                 the experience, and forcing them into one would put them
     *                 in competition with facets they have nothing to do with.
     * @param examples concrete phrasings, embedded alongside name and
     *                 description. A slug on its own is far too short to carry
     *                 meaning: embedding "intimate" loses to "live-music" for
     *                 the query "a small room, close to the performer";
     *                 embedding the definition wins it outright.
     */
    public record Tag(String slug, String name, String description,
                      String dim, String examples) {

        /** True when this tag can be matched against a facet, rather than only excluded. */
        public boolean isMatchable() { return dim != null; }

        /** What gets embedded. Computed once per tag, so length costs nothing at query time. */
        public String embeddingText() {
            return name + ". " + description
                 + (examples == null || examples.isBlank() ? "" : " " + examples);
        }
    }

    /**
     * @param embedded whether facet values on this dim get a stored vector.
     *                 Only the three dims users actually phrase preferences in
     *                 are embedded; the rest are kept as plain text, used for
     *                 rendering result rows and for the compare projection.
     *                 Promote a dim to embedded only when {@code dim_frequency}
     *                 telemetry shows queries reaching for it.
     */
    public record Dim(String name, String description, boolean embedded) {}

    // Six tags were removed from this list after the first review: comedy,
    // workshop, exhibition, food-drink, festival-outdoor and intimate.
    //
    // All fifteen original tags were written in a single commit fifteen hours
    // before the first event was ingested, so every one of them was a guess
    // about what a ticketing catalogue might hold. Eight guesses matched the
    // corpus. Six did not, and an unmatched tag is not inert: across 92 events
    // those six appeared in 173 candidate shortlists, took first place ten
    // times — every one of them wrong, including a Formula 1 race tagged
    // 'workshop' on a 0.495-to-0.495 tie — and were carried by no event at all.
    // At query time one of them captured "somewhere I can learn something" at
    // 0.594 and then had nothing to return, deleting the only signal that
    // request contained.
    //
    // The reason to guess was that adding a tag meant editing this file and
    // redeploying. That is no longer true: 'broadcast' was created with an
    // INSERT, embedded itself, rebuilt its candidate lists and reached the
    // query path across one restart. So this list holds only vocabulary the
    // corpus has been observed to need, and anything else is added when a
    // reviewer meets a facet nothing covers.
    public static final List<Tag> TAGS = List.of(
            new Tag(TAG_LIVE_MUSIC, "Live Music",
                    "A live musical performance: a concert, gig, tour date, DJ set or "
                    + "recital, where musicians perform for an audience.",
                    DIM_FORMAT,
                    "Examples: a band playing on stage, a stadium tour date, a DJ set "
                    + "in a club, a solo singer with a live band, an orchestral concert."),
            new Tag(TAG_PERFORMING_ARTS, "Performing Arts",
                    "A staged performance: theatre, musical, opera, ballet, dance or "
                    + "circus, performed by a company for a seated audience.",
                    DIM_FORMAT,
                    "Examples: a stage musical with costumes and choreography, a ballet, "
                    + "a play, an opera, a dance company touring a production."),
            new Tag(TAG_SPORTS, "Sports",
                    "A competitive sporting event: a match, race, fight, tournament or "
                    + "championship, where the outcome is decided on the day.",
                    DIM_FORMAT,
                    "Examples: a football match between two clubs, a Grand Prix race, "
                    + "a championship fight, a tennis final, a basketball game."),
            new Tag(TAG_CONFERENCE_TECH, "Conference & Tech",
                    "A professional or technical gathering: a conference, keynote, summit, "
                    + "meetup or industry talk, attended to learn and to network.",
                    DIM_FORMAT,
                    "Examples: keynote presentations and technical sessions, a developer "
                    + "conference, an industry summit, a talk followed by networking."),
            new Tag(TAG_FAMILY_KIDS, "Family & Kids",
                    "An event programmed for children and family groups, suitable for all "
                    + "ages, with no age restriction on entry.",
                    DIM_AUDIENCE,
                    "Examples: something to bring young children to, a show aimed at "
                    + "families, all ages welcome, no age restriction on entry."),
            // The physical dim has twenty-one embedded facets and deliberately
            // no tag. The obvious pair — seated and standing — was written,
            // embedded and measured, and standing beat seated on every facet
            // in the corpus including "grandstand setting" (0.535 to 0.488),
            // whose own definition contains the word grandstand. Margins ran
            // 0.002 to 0.05, which is noise.
            //
            // The cause is the model, not the wording. Antonyms occur in
            // near-identical contexts, so they sit near-identically in the
            // space: "not crowded" scores 0.771 against "crowded" on this same
            // model. A dim whose answers are opposites of each other cannot be
            // decided by cosine, and a tag that cannot be decided is worse than
            // no tag — it is assigned confidently and wrongly. Answering
            // "seated or standing?" needs a structured field, not a vector.

            new Tag(TAG_LARGE_SCALE, "Large Scale",
                    "A big-crowd event in a stadium, arena or large outdoor site, with an "
                    + "audience in the thousands and a busy, high-energy atmosphere.",
                    DIM_SCALE,
                    "Examples: a stadium filled with tens of thousands, an arena crowd, "
                    + "a packed outdoor site, thousands of people in one place."),

            // ── Exclusion-only: no dim ──────────────────────────────────────
            // Neither of these answers a dimension of the experience. headliner
            // is about an artist's fame and late-night about a start time, so
            // matching a facet against them would put them in competition with
            // content they have nothing to do with. They stay reachable through
            // NOT EXISTS, which is the only way anyone uses them.
            new Tag(TAG_HEADLINER, "Headliner",
                    "Features a well-known headline act, artist, team or speaker whose name "
                    + "is itself the reason most of the audience is attending.",
                    null, null),
            new Tag(TAG_LATE_NIGHT, "Late Night",
                    "Starts late in the evening and runs into the night, aimed at an "
                    + "audience out for the night rather than an early finish.",
                    null, null)
    );

    public static final List<Dim> DIMS = List.of(
            new Dim(DIM_FORMAT,
                    "What actually happens on stage or on the field, in concrete terms. "
                    + "The kind of performance or activity, not how it feels.",
                    true),
            new Dim(DIM_ATMOSPHERE,
                    "The mood and energy in the room: calm or intense, celebratory or "
                    + "contemplative, loud or quiet, fast-paced or unhurried.",
                    true),
            new Dim(DIM_PHYSICAL,
                    "The physical experience of being there: seated or standing, indoor or "
                    + "outdoor, lighting, noise level, how much moving around is involved.",
                    true),
            new Dim(DIM_DURATION,
                    "How long it runs and whether it is a single sitting, an evening with "
                    + "an interval, or something spread over days.",
                    false),
            new Dim(DIM_AUDIENCE,
                    "What KIND of person it is programmed for, including any age "
                    + "restriction or assumed familiarity with the subject. Not where "
                    + "they come from and not how they watch — \"international fans\" "
                    + "and \"television audiences\" describe reach, not the kind of "
                    + "person the event is for.",
                    false),
            new Dim(DIM_SCALE,
                    "How big the crowd IN THE ROOM is, in the terms a person standing "
                    + "there would notice: a full stadium, a packed club, a half-empty "
                    + "gallery. Viewers watching from elsewhere are not scale — "
                    + "television audiences, streaming numbers, worldwide acclaim, "
                    + "touring history and box-office totals all belong to other "
                    + "dimensions or to none.",
                    false),
            new Dim(DIM_PARTICIPATION,
                    "Whether the audience watches passively or takes part — singing along, "
                    + "dancing, asking questions, making something.",
                    false),
            new Dim(DIM_SETTING,
                    "The kind of venue and its character: a converted warehouse, a concert "
                    + "hall, a public park, a hotel ballroom.",
                    false)
    );

    // ── Derived lookups ──────────────────────────────────────────────────────

    /** Every valid tag slug. Anything outside this set must go through tag snapping. */
    public static final Set<String> TAG_SLUGS =
            TAGS.stream().map(Tag::slug).collect(Collectors.toUnmodifiableSet());

    /** Every valid dim name. A facet on an unknown dim is dropped, not stored. */
    public static final Set<String> DIM_NAMES =
            DIMS.stream().map(Dim::name).collect(Collectors.toUnmodifiableSet());

    /**
     * Dims whose facet values carry a stored vector.
     *
     * <p>Two sources, unioned, and the second half is a consistency rule rather
     * than a preference:
     *
     * <ul>
     *   <li>Dims marked {@code embedded} — the ones users phrase preferences in,
     *       chosen by hand.</li>
     *   <li><b>Every dim any tag claims.</b> A tag is matched by comparing it
     *       against facets on its own dim, so a tag on an unembedded dim can
     *       never be suggested for anything. It is not weakly matched — it is
     *       unreachable.</li>
     * </ul>
     *
     * <p>The union is derived rather than listed because the two sets were once
     * maintained separately and silently stopped overlapping: facets were
     * embedded on {format, atmosphere, physical} while tags lived on {format,
     * scale, audience}. Only format was in both, so {@code intimate},
     * {@code large-scale} and {@code family-kids} could not be assigned to any
     * event — all three matched their queries correctly when tested directly,
     * and none of them ever ran. Deriving the union makes adding a tag on a new
     * dim enough; nobody has to remember this rule.
     */
    public static final Set<String> EMBEDDED_DIMS = Stream.concat(
                    DIMS.stream().filter(Dim::embedded).map(Dim::name),
                    TAGS.stream().map(Tag::dim).filter(java.util.Objects::nonNull))
            .collect(Collectors.toUnmodifiableSet());

    /**
     * Tags that can be matched against a facet on a given dim.
     *
     * <p>Excludes the dim-less ones. A facet is compared only against tags on
     * its own dim — cross-dim comparison flattens the space, and a phrase about
     * room size starts matching a tag about music.
     */
    public static List<Tag> matchableOn(String dim) {
        return TAGS.stream().filter(t -> dim != null && dim.equals(t.dim())).toList();
    }

    public static boolean isKnownTag(String slug) { return slug != null && TAG_SLUGS.contains(slug); }
    public static boolean isKnownDim(String dim)  { return dim  != null && DIM_NAMES.contains(dim);  }
    public static boolean isEmbedded(String dim)  { return dim  != null && EMBEDDED_DIMS.contains(dim); }

    // ── The shared prompt fragment ───────────────────────────────────────────

    /**
     * The vocabulary block injected verbatim into both the ingestion prompt and
     * the extract prompt.
     *
     * <p>This method exists so there is exactly one place the vocabulary can be
     * edited. Do not paste an abridged version into a prompt template — the
     * whole point is that neither side can drift from the other. It is a pure
     * function of compile-time constants, so it also caches well as the static
     * prefix of a cached prompt.
     */
    /**
     * The dimension list alone — what the ingestion prompt needs.
     *
     * <p>Ingestion used to receive the tag catalogue too, and ask the model for
     * a {@code tags} array alongside its facets. Tags are no longer produced by
     * the model at all: a facet earns its tag by being embedded and matched
     * against tag definitions on its own dim, so the tag inherits the facet's
     * grounded span as evidence. A label the model simply asserts has nothing
     * behind it.
     *
     * <p>Removing the catalogue is also what makes the design scale. Listing
     * every tag with its definition costs 2,223 characters on every single
     * event, and that figure grows with the vocabulary — at a hundred tags the
     * catalogue would dominate the prompt. Retrieval by vector has no such
     * term.
     */
    public static String promptBlock() {
        StringBuilder sb = new StringBuilder(1024);

        sb.append("FACET DIMENSIONS — closed set of labels.\n")
          .append("Emit any number of facets per dimension, including none. ")
          .append("Only state what the source actually says or directly implies; ")
          .append("leaving a dimension empty is correct when the source is silent ")
          .append("about it. Never fill a dimension to make the output look complete.\n");
        for (Dim d : DIMS) {
            sb.append("  ").append(d.name()).append(" — ").append(d.description()).append('\n');
        }

        return sb.toString();
    }

}
