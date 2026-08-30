package com.ticketing.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SearchRequest {

    /**
     * What the person typed, verbatim.
     *
     * <p>Capped because the extractor's job is to split one request, not to
     * digest a document — and because every character reaches a model that
     * charges for them in latency.
     */
    @NotBlank
    @Size(max = 500)
    private String message;

    /**
     * City to assume when the message names none.
     *
     * <p>The caller supplies it from session, profile or IP. Its absence is not
     * an error: an unconstrained search returns worse results, while stopping
     * to ask "which city?" before showing anything is the form this design
     * exists to avoid.
     */
    private String city;
}
