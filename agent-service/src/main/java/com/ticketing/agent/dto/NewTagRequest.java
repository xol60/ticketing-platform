package com.ticketing.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * A tag a reviewer wants to add, because no existing tag covered a facet.
 *
 * <p>What makes this defensible rather than arbitrary is where it comes from.
 * A facet that reached this point has already quoted a verbatim span from a
 * real description and survived four deterministic gates, and none of the
 * existing tags matched it. The facet is the evidence; the tag is written in
 * response to it. Writing a tag for an <em>empty</em> slot — a dim the model
 * said nothing about — would have no such anchor, which is why that is not
 * offered.
 */
@Data
public class NewTagRequest {

    @NotBlank
    @Pattern(regexp = "[a-z]+(-[a-z]+)*",
             message = "slug is kebab-case — it is used verbatim as a DB key and a prompt token")
    private String slug;

    @NotBlank
    private String name;

    /**
     * What the tag means, in the register a person would use to ask for it.
     *
     * <p>The floor is not arbitrary. A tag is embedded from name, description
     * and examples together, and short text is dominated by whichever tokens
     * happen to be present rather than by meaning: measured on this model, the
     * slug {@code intimate} scored 0.556 against a query it should have won and
     * lost to {@code live-music}, while the full definition scored 0.819 and
     * won. The existing tags run 244 to 329 characters.
     */
    @NotBlank
    @Size(min = 80, message = "a definition this short embeds to noise — see the field docs")
    private String description;

    /**
     * Concrete phrasings, embedded alongside the definition.
     *
     * <p>This is also where synonyms go. There is no separate synonym field
     * because the embedding text is name + description + examples, so a synonym
     * and an example are the same thing to the model, and two columns holding
     * one kind of content drift apart.
     */
    @NotBlank
    private String examples;

    /** One of the eight dims, or null for an exclusion-only tag. */
    private String dim;

    /**
     * The event whose facet prompted this. Optional, and strongly encouraged.
     *
     * <p>Attaching it is what guarantees a tag created here is never empty.
     * Six seed tags written before any data existed ended up carried by no
     * event at all, and an empty tag is not inert — those six took first place
     * ten times, every one of them wrong.
     */
    private String attachToEventId;
}
