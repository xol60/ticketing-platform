package com.ticketing.agent.ingest;

import com.ticketing.agent.domain.model.City;
import com.ticketing.agent.domain.repository.CityAliasRepository;
import com.ticketing.agent.domain.repository.CityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Turns the free-text {@code venue_city} into a city id.
 *
 * <h3>Why this is not just a string comparison</h3>
 * City is a hard filter, and §6.4's first rule says anything SQL can decide
 * exactly must never reach the vector — the string "new york" inside an
 * embedding matches a Boston event whose description mentions New York in
 * passing. So the filter compares an integer.
 *
 * <p>Getting to that integer is the work. The catalogue stores "Hà Nội" and
 * "TP. Hồ Chí Minh"; a user types "hanoi" or "ha noi" on a keyboard with no
 * diacritics. Folding both sides to the same form is what makes those meet.
 *
 * <h3>Created on first sight, not seeded</h3>
 * A seeded list drifts from the corpus the moment someone adds an event in a
 * city nobody anticipated — and drifts silently, because the symptom is a
 * search that quietly returns nothing rather than an error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CityResolver {

    private final CityRepository      cityRepository;
    private final CityAliasRepository aliasRepository;

    /**
     * Resolves, creating the city and its aliases if this is the first event
     * seen there.
     *
     * @return the city id, or null when the event carries no city — which
     *         happens for half-populated rows and must not fail ingestion
     */
    @Transactional
    public Integer resolve(String rawCity) {
        if (rawCity == null || rawCity.isBlank()) return null;

        String canonical = rawCity.trim();

        City city = cityRepository.findByCanonicalName(canonical)
                .orElseGet(() -> {
                    City created = cityRepository.save(
                            City.builder().canonicalName(canonical).build());
                    log.info("New city registered: {}", canonical);
                    return created;
                });

        // Re-registered on every ingest rather than only on creation. Aliases
        // are an upsert that does nothing when they already exist, and the
        // alternative — writing them once at creation — leaves older cities
        // permanently missing any alias form added later.
        for (String alias : aliasesFor(canonical)) {
            aliasRepository.registerAlias(alias, city.getId());
        }

        return city.getId();
    }

    /**
     * The folded spellings a person might type for this city.
     *
     * <p>Three forms, and the third is the one that matters for Vietnamese:
     * lowercase ("new york"), accent-stripped ("ha noi"), and accent-stripped
     * with spaces removed ("hanoi"). Someone typing on a plain keyboard
     * produces the last two; the catalogue holds the first.
     */
    static Set<String> aliasesFor(String canonical) {
        Set<String> out = new LinkedHashSet<>();

        String lower = canonical.toLowerCase().trim();
        out.add(lower);

        // NFD splits an accented character into base plus combining mark, so
        // stripping the marks leaves plain ASCII: "Hà Nội" → "ha noi".
        String stripped = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                // Vietnamese đ/Đ carries no combining mark and survives NFD
                // untouched, so it needs its own substitution.
                .replace('đ', 'd')
                // Punctuation in "TP. Hồ Chí Minh" is not something a user types.
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (!stripped.isEmpty()) {
            out.add(stripped);
            out.add(stripped.replace(" ", ""));
        }

        out.removeIf(String::isEmpty);
        return out;
    }
}
