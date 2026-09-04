package com.ticketing.common.agent;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The closed vocabulary for the event recommendation agent: eight facet
 * dimensions, and nothing else.
 *
 * <h3>Why this lives in common-lib and not in agent-service</h3>
 * Two prompts consume this vocabulary, and they run in different code paths:
 *
 * <ul>
 *   <li><b>Ingestion</b> (offline, once per event) — turns an event description
 *       into facets. Writes the {@code dim} labels.</li>
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
 * <h3>There are no tags here, deliberately</h3>
 * Tags used to be defined in this file and pushed into the {@code tag} table on
 * every boot. They are not any more: the table is the definition, and the only
 * thing that writes to it is a reviewer.
 *
 * <p>The reason is what happened to the fifteen tags that were once listed
 * here. All fifteen were written in a single commit fifteen hours before the
 * first event was ingested, so every one was a guess about what a ticketing
 * catalogue might hold. Six never matched anything, and an unmatched tag is not
 * inert: across 92 events those six appeared in 173 candidate shortlists, took
 * first place ten times — every one wrong, including a Formula 1 race tagged
 * {@code workshop} on a 0.495-to-0.495 tie — and were carried by no event at
 * all. At query time one of them captured "somewhere I can learn something" at
 * 0.594 and then had nothing to return, deleting the only signal that request
 * contained. Two more, {@code seated} and {@code standing}, were antonyms
 * cosine could not separate at all.
 *
 * <p>The reason to guess was that adding a tag meant editing this file and
 * redeploying. That stopped being true once the curation flow existed: a tag is
 * now created by a person who has met a facet nothing covers, writes the
 * definition, and sees it embed, rebuild its candidate lists and reach the
 * query path without a deploy. A vocabulary that grows from evidence has no use
 * for a compile-time seed, and a seed that outranks nothing is only a second
 * copy waiting to disagree with the first.
 *
 * <p>What Java still owns is which dims carry vectors — see
 * {@link #EMBEDDED_DIMS}, which is a property of the prompts, not of the
 * vocabulary.
 *
 * <p>Event content is English, so this vocabulary is English.
 */
public final class Taxonomy {

    private Taxonomy() {}

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
     * @param embedded whether facet values on this dim get a stored vector.
     *                 Only the dims users actually phrase preferences in are
     *                 embedded; the rest are kept as plain text, used for
     *                 rendering result rows and for the compare projection.
     *
     *                 <p>This is also the gate on where a tag may live. A tag
     *                 is matched by comparing it against facets on its own dim,
     *                 so a tag on an unembedded dim is not weakly matched — it
     *                 is unreachable. The curation service rejects one, and
     *                 promoting a dim is a deliberate edit here.
     */
    public record Dim(String name, String description, boolean embedded) {}

    public static final List<Dim> DIMS = List.of(
            new Dim(DIM_FORMAT,
                    "What actually happens on stage or on the field, in concrete terms. "
                    + "The kind of performance or activity, not how it feels.",
                    true),
            new Dim(DIM_ATMOSPHERE,
                    "The mood and energy in the room: calm or intense, celebratory or "
                    + "contemplative, loud or quiet, fast-paced or unhurried.",
                    true),
            // Embedded, but note what a tag on this dim is up against. The
            // obvious pair — seated and standing — was written, embedded and
            // measured, and standing beat seated on every facet in the corpus
            // including "grandstand setting" (0.535 to 0.488), whose own
            // definition contains the word grandstand. Margins ran 0.002 to
            // 0.05, which is noise. The cause is the model, not the wording:
            // antonyms occur in near-identical contexts, so they sit
            // near-identically in the space — "not crowded" scores 0.771
            // against "crowded" on this same model. A dim whose answers are
            // opposites of each other cannot be decided by cosine, and a tag
            // that cannot be decided is worse than no tag: it is assigned
            // confidently and wrongly.
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
                    true),
            new Dim(DIM_SCALE,
                    "How big the crowd IN THE ROOM is, in the terms a person standing "
                    + "there would notice: a full stadium, a packed club, a half-empty "
                    + "gallery. Viewers watching from elsewhere are not scale — "
                    + "television audiences, streaming numbers, worldwide acclaim, "
                    + "touring history and box-office totals all belong to other "
                    + "dimensions or to none.",
                    true),
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

    /** Every valid dim name. A facet on an unknown dim is dropped, not stored. */
    public static final Set<String> DIM_NAMES =
            DIMS.stream().map(Dim::name).collect(Collectors.toUnmodifiableSet());

    /**
     * Dims whose facet values carry a stored vector.
     *
     * <p>{@code scale} and {@code audience} are here because tags live on them,
     * and were once derived from the tag list for exactly that reason: the two
     * sets were maintained separately, silently stopped overlapping — facets
     * embedded on {format, atmosphere, physical} while tags lived on {format,
     * scale, audience} — and three tags became unreachable. All three matched
     * their queries correctly when tested directly, and none of them ever ran.
     *
     * <p>With the tag list gone from Java the union has nothing to derive from,
     * so the rule is enforced from the other end instead: the curation service
     * refuses to create a tag on a dim that is not in this set, with an error a
     * reviewer can read, rather than accepting one that would never fire.
     */
    public static final Set<String> EMBEDDED_DIMS = DIMS.stream()
            .filter(Dim::embedded).map(Dim::name)
            .collect(Collectors.toUnmodifiableSet());

    public static boolean isKnownDim(String dim) { return dim != null && DIM_NAMES.contains(dim);     }
    public static boolean isEmbedded(String dim) { return dim != null && EMBEDDED_DIMS.contains(dim); }

    // ── The shared prompt fragment ───────────────────────────────────────────

    /**
     * The dimension list, injected verbatim into both the ingestion prompt and
     * the extract prompt.
     *
     * <p>This method exists so there is exactly one place the vocabulary can be
     * edited. Do not paste an abridged version into a prompt template — the
     * whole point is that neither side can drift from the other. It is a pure
     * function of compile-time constants, so it also caches well as the static
     * prefix of a cached prompt.
     *
     * <p>No tag reaches this block. A facet earns its tag by being embedded and
     * matched against tag definitions on its own dim, so the tag inherits the
     * facet's grounded span as evidence; a label the model simply asserts has
     * nothing behind it. Removing the catalogue is also what makes the design
     * scale — listing every tag with its definition cost 2,223 characters on
     * every single event, and that figure grew with the vocabulary. Retrieval
     * by vector has no such term.
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
