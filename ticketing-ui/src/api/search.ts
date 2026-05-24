import type { AxiosResponse } from 'axios';
import api from './client';
import type {
  ApiResponse,
  EventSearchPage,
  AutocompleteSuggestion,
} from '../types';

/**
 * Search-service client.
 *
 * Both endpoints are public (no auth) and hit the Elasticsearch derived index
 * via the API gateway. See README → "Search subsystem" for the full feature
 * matrix (multi-field boosting, fuzzy AUTO, edge_ngram autocomplete).
 */
export const searchApi = {
  /**
   * Multi-field full-text search with optional facet filters.
   * Falls through to an empty page when the query is blank — callers should
   * guard with `if (!query.trim()) return` before invoking.
   */
  searchEvents: (params: {
    q: string;
    category?: string;
    genre?: string;
    from?: number;
    size?: number;
  }) =>
    api
      .get<ApiResponse<EventSearchPage>>('/api/search/events', { params })
      .then((r: AxiosResponse<ApiResponse<EventSearchPage>>) => r.data.data),

  /**
   * Autocomplete-as-you-type. Designed to be called on every keystroke from
   * the second character onward. The server returns at most {@link size}
   * suggestions (hard-capped at 10).
   *
   * <p>Optional {@code signal} parameter lets React Query (or any caller)
   * abort the in-flight HTTP request when a newer keystroke arrives. Without
   * this, fast typing leaves a queue of racing requests — the slowest one
   * eventually overwrites the dropdown with stale suggestions, AND
   * search-service receives N parallel queries it doesn't need.
   */
  suggestEvents: (q: string, size = 5, signal?: AbortSignal) =>
    api
      .get<ApiResponse<AutocompleteSuggestion[]>>(
        '/api/search/events/suggest',
        { params: { q, size }, signal },
      )
      .then((r: AxiosResponse<ApiResponse<AutocompleteSuggestion[]>>) => r.data.data),
};
