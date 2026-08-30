package com.ticketing.agent.validation;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Text folding shared by the grounding and overlap gates.
 *
 * <p>Two different comparisons need two different amounts of folding, and
 * conflating them would break both:
 *
 * <ul>
 *   <li>{@link #fold(String)} — for <b>grounding</b>. Normalises only what a
 *       model might reasonably alter while still copying: whitespace runs,
 *       letter case, Unicode composition, and the typographic quotes and
 *       dashes that get swapped in transit. It does <em>not</em> stem or drop
 *       words, because grounding asks whether the model copied the source,
 *       and any folding beyond formatting starts letting paraphrase through.</li>
 *   <li>{@link #contentWords(String)} — for <b>overlap</b>. Tokenises, drops
 *       stopwords, and stems. Here paraphrase is the point: a facet
 *       <em>should</em> restate its span rather than copy it, so "crowd sings
 *       along" must be recognised as covered by "80,000 people singing every
 *       word back" on the shared stem "sing".</li>
 * </ul>
 *
 * <h3>On the stemmer</h3>
 * Suffix stripping, not Porter. It handles the endings that actually separate
 * a facet from its span — plurals, gerunds, past tense, adverbs — and leaves
 * everything else alone. A full stemmer would buy accuracy this gate cannot
 * use: the overlap threshold is a third, so one or two extra stem collisions
 * change nothing, while a wrong aggressive stem silently merges unrelated
 * words and weakens the check.
 *
 * <p>Non-English text passes through essentially unstemmed, which is correct
 * — event descriptions are English, and Vietnamese venue and city names are
 * proper nouns that should match literally or not at all.
 */
public final class TextNormalizer {

    private TextNormalizer() {}

    /**
     * Words carrying no evidential weight. Kept deliberately short: every word
     * removed here is one that can no longer support a facet, so the list
     * covers only function words that appear in any sentence regardless of
     * meaning.
     */
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "of", "at", "by", "for",
            "with", "about", "into", "through", "to", "from", "in", "on", "off",
            "over", "under", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "can",
            "could", "should", "may", "might", "must", "shall", "this", "that",
            "these", "those", "it", "its", "as", "than", "then", "so", "such",
            "there", "here", "when", "where", "which", "who", "whom", "while",
            "you", "your", "their", "they", "them", "his", "her", "our", "we"
    );

    /** Minimum length for a token to count as content. Shorter tokens are noise. */
    private static final int MIN_TOKEN_LENGTH = 3;

    /**
     * Folds formatting differences only — the comparison basis for grounding.
     *
     * <p>Order matters: Unicode composition first (so a decomposed accent
     * becomes one character before anything measures length), then the
     * punctuation substitutions, then case, then whitespace collapse.
     */
    public static String fold(String text) {
        if (text == null) return "";

        String s = Normalizer.normalize(text, Normalizer.Form.NFC);

        // Typographic characters a model swaps for ASCII while otherwise
        // copying faithfully. Without this, a curly apostrophe in the source
        // fails an otherwise perfect quotation.
        s = s.replace('‘', '\'').replace('’', '\'')   // ' '
             .replace('“', '"').replace('”', '"')     // " "
             .replace('–', '-').replace('—', '-')     // – —
             .replace('…', '.')                            // …
             .replace(' ', ' ');                           // nbsp

        return s.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    /**
     * Stemmed content words — the comparison basis for overlap.
     *
     * <p>Returns a {@link LinkedHashSet} so iteration order is stable, which
     * keeps rejection detail messages reproducible across runs.
     */
    public static Set<String> contentWords(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;

        // Split on anything that is not a letter or digit. Unicode-aware, so
        // accented characters stay inside their token rather than splitting it.
        Arrays.stream(fold(text).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> t.length() >= MIN_TOKEN_LENGTH)
                .filter(t -> !STOPWORDS.contains(t))
                .map(TextNormalizer::stem)
                .filter(t -> !t.isEmpty())
                .forEach(out::add);

        return out;
    }

    /**
     * Strips the inflectional endings that separate a paraphrase from its
     * source. Longest suffix first, and never below a four-character root —
     * shortening past that starts merging words that only look alike.
     */
    static String stem(String token) {
        String t = token;

        // Order is significant: "singing" must lose "ing", not "g".
        if (t.length() > 5 && t.endsWith("ingly")) return t.substring(0, t.length() - 5);
        if (t.length() > 5 && t.endsWith("ation")) return t.substring(0, t.length() - 5) + "at";
        if (t.length() > 4 && t.endsWith("ing"))   return trimDoubledConsonant(t.substring(0, t.length() - 3));
        if (t.length() > 4 && t.endsWith("edly"))  return t.substring(0, t.length() - 4);
        if (t.length() > 4 && t.endsWith("ies"))   return t.substring(0, t.length() - 3) + "y";
        if (t.length() > 4 && t.endsWith("ied"))   return t.substring(0, t.length() - 3) + "y";
        if (t.length() > 4 && t.endsWith("ely"))   return t.substring(0, t.length() - 3);
        if (t.length() > 4 && t.endsWith("ed"))    return trimDoubledConsonant(t.substring(0, t.length() - 2));
        if (t.length() > 4 && t.endsWith("ly"))    return t.substring(0, t.length() - 2);
        if (t.length() > 4 && t.endsWith("es"))    return t.substring(0, t.length() - 2);
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) return t.substring(0, t.length() - 1);

        return t;
    }

    /**
     * "stopped" → "stopp" → "stop". English doubles a final consonant before
     * -ing/-ed; leaving the double in place would stop the stem matching the
     * base form, which is the whole point of stemming here.
     */
    private static String trimDoubledConsonant(String s) {
        int n = s.length();
        if (n >= 3
                && s.charAt(n - 1) == s.charAt(n - 2)
                && "bdfglmnprt".indexOf(s.charAt(n - 1)) >= 0) {
            return s.substring(0, n - 1);
        }
        return s;
    }
}
