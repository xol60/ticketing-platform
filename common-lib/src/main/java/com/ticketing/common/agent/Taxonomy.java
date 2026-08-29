package com.ticketing.common.agent;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    // ── Tag slugs — category (10) ────────────────────────────────────────────
    public static final String TAG_LIVE_MUSIC      = "live-music";
    public static final String TAG_PERFORMING_ARTS = "performing-arts";
    public static final String TAG_SPORTS          = "sports";
    public static final String TAG_CONFERENCE_TECH = "conference-tech";
    public static final String TAG_EXHIBITION      = "exhibition";
    public static final String TAG_FOOD_DRINK      = "food-drink";
    public static final String TAG_FESTIVAL        = "festival-outdoor";
    public static final String TAG_COMEDY          = "comedy";
    public static final String TAG_WORKSHOP        = "workshop";
    public static final String TAG_FAMILY_KIDS     = "family-kids";

    // ── Tag slugs — attribute (5) ────────────────────────────────────────────
    public static final String TAG_HEADLINER   = "headliner";
    public static final String TAG_LARGE_SCALE = "large-scale";
    public static final String TAG_INTIMATE    = "intimate";
    public static final String TAG_LOW_COST    = "low-cost";
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
     * @param kind {@link Kind#CATEGORY} tags answer "what type of event is this";
     *             {@link Kind#ATTRIBUTE} tags answer "what is it like". A query
     *             typically filters on at most one category but may exclude
     *             several attributes.
     */
    public record Tag(String slug, String name, String description, Kind kind) {
        public enum Kind { CATEGORY, ATTRIBUTE }
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

    public static final List<Tag> TAGS = List.of(
            new Tag(TAG_LIVE_MUSIC, "Live Music",
                    "A live musical performance: a concert, gig, tour date, DJ set or "
                    + "recital, where musicians perform for an audience.",
                    Tag.Kind.CATEGORY),
            new Tag(TAG_PERFORMING_ARTS, "Performing Arts",
                    "A staged performance: theatre, musical, opera, ballet, dance or "
                    + "circus, performed by a company for a seated audience.",
                    Tag.Kind.CATEGORY),
            new Tag(TAG_SPORTS, "Sports",
                    "A competitive sporting event: a match, race, fight, tournament or "
                    + "championship, where the outcome is decided on the day.",
                    Tag.Kind.CATEGORY),
            new Tag(TAG_CONFERENCE_TECH, "Conference & Tech",
                    "A professional or technical gathering: a conference, keynote, summit, "
                    + "meetup or industry talk, attended to learn and to network.",
                    Tag.Kind.CATEGORY),
            new Tag(TAG_EXHIBITION, "Exhibition & Visual Arts",
                    "A visual arts showing: a gallery exhibition, museum show, art fair or "
                    + "installation, viewed at the visitor's own pace.",
                    Tag.Kind.CATEGORY),
            new Tag(TAG_FOOD_DRINK, "Food & Drink",
                    "An event built around eating and drinking: a food festival, tasting, "
                    + "supper club, brewery tour or pop-up restaurant.",
                    Tag.Kind.CATEGORY),
            new Tag(TAG_FESTIVAL, "Festival & Outdoor",
                    "A multi-act or multi-day outdoor gathering: a music festival, street "
                    + "fair, parade or countdown event, usually standing and weather-exposed.",
                    Tag.Kind.CATEGORY),
            new Tag(TAG_COMEDY, "Comedy & Light Entertainment",
                    "A comedy or light entertainment show: stand-up, improv, a live podcast "
                    + "recording, a panel show or a talk show taping.",
                    Tag.Kind.CATEGORY),
            new Tag(TAG_WORKSHOP, "Workshop & Learning",
                    "A hands-on session where attendees make or practise something: a short "
                    + "course, masterclass, tasting class or skills workshop.",
                    Tag.Kind.CATEGORY),
            new Tag(TAG_FAMILY_KIDS, "Family & Kids",
                    "An event programmed for children and family groups, suitable for all "
                    + "ages, with no age restriction on entry.",
                    Tag.Kind.CATEGORY),

            new Tag(TAG_HEADLINER, "Headliner",
                    "Features a well-known headline act, artist, team or speaker whose name "
                    + "is itself the reason most of the audience is attending.",
                    Tag.Kind.ATTRIBUTE),
            new Tag(TAG_LARGE_SCALE, "Large Scale",
                    "A big-crowd event in a stadium, arena or large outdoor site, with an "
                    + "audience in the thousands and a busy, high-energy atmosphere.",
                    Tag.Kind.ATTRIBUTE),
            new Tag(TAG_INTIMATE, "Intimate",
                    "A small-room event with a few hundred people or fewer, where the "
                    + "audience is close to the performer and the atmosphere is personal.",
                    Tag.Kind.ATTRIBUTE),
            new Tag(TAG_LOW_COST, "Free or Low Cost",
                    "Free to attend, or priced low enough to be an easy spontaneous choice "
                    + "rather than a planned expense.",
                    Tag.Kind.ATTRIBUTE),
            new Tag(TAG_LATE_NIGHT, "Late Night",
                    "Starts late in the evening and runs into the night, aimed at an "
                    + "audience out for the night rather than an early finish.",
                    Tag.Kind.ATTRIBUTE)
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
                    "Who it is programmed for, including any age restriction or assumed "
                    + "familiarity with the subject.",
                    false),
            new Dim(DIM_SCALE,
                    "How big the crowd is, in the terms a person would notice: a full "
                    + "stadium, a packed club, a half-empty gallery.",
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

    /** Dims whose facet values carry a stored vector — currently format, atmosphere, physical. */
    public static final Set<String> EMBEDDED_DIMS = DIMS.stream()
            .filter(Dim::embedded).map(Dim::name).collect(Collectors.toUnmodifiableSet());

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
    public static String promptBlock() {
        StringBuilder sb = new StringBuilder(2048);

        sb.append("TAGS — closed set. Use only these slugs; never invent one.\n");
        for (Tag t : TAGS) {
            sb.append("  ").append(t.slug())
              .append(" (").append(t.kind().name().toLowerCase()).append(") — ")
              .append(t.description()).append('\n');
        }

        sb.append("\nFACET DIMENSIONS — closed set of labels.\n")
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
