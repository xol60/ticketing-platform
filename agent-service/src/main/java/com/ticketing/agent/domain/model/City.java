package com.ticketing.agent.domain.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * A city the corpus actually contains.
 *
 * <p>Rows are created on first sight during ingest rather than seeded from a
 * fixed list, so this table cannot drift from the events that exist. The
 * canonical name is whatever ticket-service stores ("Hà Nội", "TP. Hồ Chí
 * Minh"); the folded forms a user types live in {@link CityAlias}.
 *
 * <p>City is the one filter that is never relaxed when a search returns
 * nothing. Widening price or dates gives a worse answer; widening city gives a
 * useless one — nobody asking for tonight in Hanoi is helped by a show in
 * Sydney.
 */
@Entity
@Table(name = "city")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class City {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "canonical_name", nullable = false, unique = true)
    private String canonicalName;
}
