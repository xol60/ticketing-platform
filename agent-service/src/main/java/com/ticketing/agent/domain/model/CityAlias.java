package com.ticketing.agent.domain.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * A folded spelling that resolves to a {@link City}.
 *
 * <p>The alias is the lookup key, stored already normalised: lowercase, accents
 * stripped, punctuation removed. That is what makes "hanoi", "ha noi" and
 * "Hà Nội" all land on the same row — a Vietnamese user on a plain keyboard
 * types the first, and the corpus stores the third.
 *
 * <p>Aliases are generated at ingest from the canonical name. Adding a hand
 * written one ("saigon" → "TP. Hồ Chí Minh") is a plain INSERT and needs no
 * code change, which is the reason this is a table and not a switch statement.
 */
@Entity
@Table(name = "city_alias")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CityAlias {

    @Id
    @Column(nullable = false, updatable = false)
    private String alias;

    @Column(name = "city_id", nullable = false)
    private Integer cityId;
}
