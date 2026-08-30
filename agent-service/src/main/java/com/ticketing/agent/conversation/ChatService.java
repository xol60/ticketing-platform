package com.ticketing.agent.conversation;

import com.ticketing.agent.conversation.ConversationState.Stage;
import com.ticketing.agent.domain.model.AgentEvent;
import com.ticketing.agent.domain.repository.AgentEventRepository;
import com.ticketing.agent.search.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One conversational turn: load state, merge, decide what kind of turn this is,
 * act, save.
 *
 * <h3>The model proposes a patch; Java owns the state</h3>
 * The extractor never sees the accumulated state and never returns it. It reads
 * one sentence and reports what that sentence said; merging is arithmetic done
 * here. Handing a model the whole state and asking for the new one invites it
 * to quietly drop a slot nobody mentioned, and the loss is invisible until a
 * search comes back wrong three turns later.
 *
 * <h3>Leaving focus is a rule, not a judgement</h3>
 * The stage machine exits {@link Stage#FOCUSED} on a fixed condition — a new
 * city, or a newly described mood — rather than asking the model whether the
 * person has moved on. A model asked that question is right most of the time,
 * and the failure mode of the remainder is being stuck answering about an event
 * the person abandoned two turns ago.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ConversationStore     store;
    private final QueryExtractor        extractor;
    private final SearchService         searchService;
    private final AgentEventRepository  eventRepository;

    public record TurnResult(SearchResult search, ConversationState state,
                             AgentEvent focused, Handoff handoff) {}

    /**
     * Base for the deep link handed back at the end of the funnel. Configurable
     * because the same service serves a local dev UI and a deployed one.
     */
    @org.springframework.beans.factory.annotation.Value("${agent.handoff.base-url:}")
    private String handoffBaseUrl;

    public TurnResult handle(String sessionId, String message, String fallbackCity) {
        ConversationState state = store.load(sessionId)
                .orElseGet(() -> ConversationState.builder().sessionId(sessionId).build());

        QueryExtraction patch = extractor.extract(message);

        // Pointing at a row is a selection, not a search. Resolved by index
        // against what the last turn actually displayed.
        if (patch.intent() == QueryExtraction.Intent.SELECT && patch.isOrdinalReference()) {
            Optional<AgentEvent> picked = resolveOrdinal(state, patch.ordinal());
            if (picked.isPresent()) {
                state.setStage(Stage.FOCUSED);
                state.setFocusedEventId(picked.get().getId());
                state.setTurnCount(state.getTurnCount() + 1);
                store.save(state);
                return new TurnResult(null, state, picked.get(), null);
            }
            // Out of range — fall through and treat the message as a search
            // rather than reporting an error about a list the person can see.
            log.debug("Ordinal {} is outside the {} results shown",
                    patch.ordinal(), state.getCandidateEventIds().size());
        }

        // The end of the funnel. The agent names an event and stops — no hold,
        // no order, no lock. Everything after this is the checkout flow that
        // already exists.
        if (patch.intent() == QueryExtraction.Intent.HANDOFF
                && state.getFocusedEventId() != null) {
            AgentEvent target = eventRepository.findById(state.getFocusedEventId()).orElse(null);
            if (target != null) {
                state.setStage(Stage.CONFIRMING);
                state.setTurnCount(state.getTurnCount() + 1);
                store.save(state);
                return new TurnResult(null, state, target, Handoff.of(target, handoffBaseUrl));
            }
        }

        // Asking about the event already chosen. Answered from the database:
        // no hard filter, no vector, no ranking.
        //
        // Gated on intent rather than on the patch looking empty. The earlier
        // test — "did this turn mention anything?" — failed on the most ordinary
        // question there is: "what time does it start" produced a stray
        // duration facet, which read as a new search and silently dropped the
        // event the person had just picked.
        if (state.getStage() == Stage.FOCUSED
                && patch.intent() == QueryExtraction.Intent.DETAIL
                && state.getFocusedEventId() != null) {
            AgentEvent focused = eventRepository.findById(state.getFocusedEventId()).orElse(null);
            if (focused != null) {
                state.setTurnCount(state.getTurnCount() + 1);
                store.save(state);
                return new TurnResult(null, state, focused, null);
            }
        }

        // Anything else is a search, so the conversation is browsing again —
        // whatever it was before. Leaving the stage untouched here reported
        // FOCUSED alongside a fresh result list, which is two different answers
        // to "where am I" in one response.
        state.setStage(Stage.BROWSING);
        state.setFocusedEventId(null);

        merge(state, patch);

        SearchResult result = searchService.search(
                toQuery(state, patch),
                state.getCity() != null ? state.getCity() : fallbackCity);

        state.setCandidateEventIds(
                result.events().stream().map(s -> s.event().getId()).toList());
        state.setTurnCount(state.getTurnCount() + 1);
        store.save(state);

        return new TurnResult(result, state, null, null);
    }

    /**
     * Applies one turn's patch to the carried state.
     *
     * <p>Three different rules, and the difference between them is the whole
     * point:
     * <ul>
     *   <li>A stated slot replaces the old value.</li>
     *   <li>An explicitly cleared slot is removed.</li>
     *   <li>An unmentioned slot is left alone — silence is not a retraction.</li>
     * </ul>
     *
     * <p>Mood is the exception: a turn that describes one replaces the previous
     * mood entirely rather than adding to it. Accumulating "something relaxing"
     * and a later "actually more upbeat" produces a query vector sitting between
     * two opposites, which matches neither.
     */
    private void merge(ConversationState state, QueryExtraction patch) {
        if (patch.city() != null)           state.setCity(patch.city());
        if (patch.dateExpression() != null) state.setDateExpression(patch.dateExpression());
        if (patch.priceMax() != null)       state.setPriceMax(patch.priceMax());
        if (!patch.excludeTags().isEmpty()) state.setExcludeTags(new ArrayList<>(patch.excludeTags()));

        for (String field : patch.clearFields()) {
            switch (field) {
                case "city"           -> state.setCity(null);
                case "dateExpression" -> state.setDateExpression(null);
                case "priceMax"       -> state.setPriceMax(null);
                case "excludeTags"    -> state.setExcludeTags(new ArrayList<>());
                default -> log.debug("Ignoring unknown clear target '{}'", field);
            }
        }

        if (!patch.vibeFacets().isEmpty()) {
            state.setVibeFacets(patch.vibeFacets().stream()
                    .map(f -> f.dim() + "|" + f.value()).toList());
        }
    }

    /**
     * Hands the accumulated state to the search path as structured data.
     *
     * <p>Replaces an earlier version that reassembled the state into a sentence
     * and re-extracted it. That round trip was the source of the worst bug in
     * this service: a city captured on turn one vanished on turn two, because
     * the sentence it rebuilt no longer parsed as one containing a city, and the
     * merge then overwrote the slot with the null it got back. State fed itself
     * into a model and came out thinner every time.
     *
     * <p>{@code properNoun} comes from the current turn only. A name is a
     * one-shot lookup, not a filter: carrying "taylor swift" forward would pin
     * every later turn to that artist no matter what the person went on to ask.
     */
    private QueryExtraction toQuery(ConversationState state, QueryExtraction patch) {
        List<FacetQuery> vibe = new ArrayList<>();
        for (String stored : state.getVibeFacets()) {
            String[] parts = stored.split("\\|", 2);
            if (parts.length == 2) vibe.add(new FacetQuery(parts[0], parts[1]));
        }
        return new QueryExtraction(
                QueryExtraction.Intent.FIND, null, List.of(), patch.properNoun(),
                state.getCity(), state.getDateExpression(), state.getPriceMax(),
                vibe, state.getExcludeTags());
    }

    private Optional<AgentEvent> resolveOrdinal(ConversationState state, int ordinal) {
        List<String> shown = state.getCandidateEventIds();
        if (ordinal < 1 || ordinal > shown.size()) return Optional.empty();
        return eventRepository.findById(shown.get(ordinal - 1));
    }
}
