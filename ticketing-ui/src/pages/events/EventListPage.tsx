import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { ticketsApi } from '../../api/tickets';
import { searchApi } from '../../api/search';
import { EventStatusBadge } from '../../components/ui/Badge';
import { PageSpinner } from '../../components/ui/Spinner';
import type { Event, EventSearchHit, AutocompleteSuggestion } from '../../types';

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Debounce a value — used to avoid firing /suggest on every keystroke. */
function useDebounced<T>(value: T, ms = 200): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return debounced;
}

/** Renders the meta line (artist + venue + city) shown under each card. */
function MetaLine({ event }: { event: Pick<Event, 'primaryArtist' | 'venueName' | 'venueCity'> }) {
  const parts: string[] = [];
  if (event.primaryArtist) parts.push(event.primaryArtist);
  if (event.venueName) parts.push(event.venueName);
  if (event.venueCity) parts.push(event.venueCity);
  if (parts.length === 0) return null;
  return (
    <p className="text-xs text-gray-500 mt-1 line-clamp-1">
      {parts.join(' · ')}
    </p>
  );
}

// ─── Card variants ────────────────────────────────────────────────────────────

/** Card used when we're listing events from /api/tickets/events (no search). */
function EventCard({ event }: { event: Event }) {
  const isOpen = event.status === 'OPEN';
  const eventDate = new Date(event.eventDate);
  const salesClose = new Date(event.salesCloseAt);
  const daysLeft = Math.max(0, Math.ceil((salesClose.getTime() - Date.now()) / 86_400_000));

  return (
    <Link to={`/events/${event.id}`} className="group block">
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden hover:shadow-md hover:border-blue-100 transition-all duration-200">
        <div className={`h-1.5 ${isOpen ? 'bg-gradient-to-r from-blue-500 to-indigo-500' : 'bg-gray-200'}`} />
        <div className="p-5">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <h3 className="text-base font-semibold text-gray-900 group-hover:text-blue-600 transition-colors line-clamp-2">
                {event.name}
              </h3>
              <MetaLine event={event} />
            </div>
            <EventStatusBadge status={event.status} />
          </div>

          {(event.category || event.genre) && (
            <div className="mt-2 flex flex-wrap gap-1.5">
              {event.category && (
                <span className="px-2 py-0.5 rounded-full text-[11px] font-medium bg-indigo-50 text-indigo-700">
                  {event.category}
                </span>
              )}
              {event.genre && (
                <span className="px-2 py-0.5 rounded-full text-[11px] font-medium bg-purple-50 text-purple-700">
                  {event.genre}
                </span>
              )}
            </div>
          )}

          <div className="mt-3 flex flex-col gap-1.5 text-sm text-gray-500">
            <div className="flex items-center gap-2">
              <span>📅</span>
              <span>{eventDate.toLocaleDateString('en-US', { dateStyle: 'medium' })}</span>
            </div>
            <div className="flex items-center gap-2">
              <span>🕐</span>
              <span>{eventDate.toLocaleTimeString('en-US', { timeStyle: 'short' })}</span>
            </div>
            {isOpen && daysLeft > 0 && (
              <div className="flex items-center gap-2 text-blue-600 font-medium">
                <span>⏳</span>
                <span>{daysLeft} day{daysLeft !== 1 ? 's' : ''} left to buy</span>
              </div>
            )}
          </div>

          <div className="mt-4 pt-3 border-t border-gray-50 flex items-center justify-between">
            <span className="text-xs text-gray-400">
              Sales close {salesClose.toLocaleDateString('en-US', { dateStyle: 'short' })}
            </span>
            <span className={`text-sm font-medium ${isOpen ? 'text-blue-600' : 'text-gray-400'}`}>
              {isOpen ? 'View tickets →' : 'Unavailable'}
            </span>
          </div>
        </div>
      </div>
    </Link>
  );
}

/**
 * Card used for search results from /api/search/events.
 *
 * <p>The hit projection lacks status / sales window, but everything in this
 * index is guaranteed OPEN (the indexer removes any non-OPEN doc), so we can
 * safely render the "On sale now" green dot without re-checking.
 */
function SearchResultCard({ hit }: { hit: EventSearchHit }) {
  const eventDate = hit.eventDate ? new Date(hit.eventDate) : null;
  return (
    <Link to={`/events/${hit.id}`} className="group block">
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden hover:shadow-md hover:border-blue-100 transition-all duration-200">
        <div className="h-1.5 bg-gradient-to-r from-blue-500 to-indigo-500" />
        <div className="p-5">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <h3 className="text-base font-semibold text-gray-900 group-hover:text-blue-600 transition-colors line-clamp-2">
                {hit.name}
              </h3>
              <MetaLine event={hit} />
            </div>
            <span
              className="px-2 py-0.5 rounded-full text-[10px] font-mono bg-gray-50 text-gray-500 shrink-0"
              title="Elasticsearch BM25 relevance score"
            >
              {hit.score.toFixed(2)}
            </span>
          </div>

          {(hit.category || hit.genre) && (
            <div className="mt-2 flex flex-wrap gap-1.5">
              {hit.category && (
                <span className="px-2 py-0.5 rounded-full text-[11px] font-medium bg-indigo-50 text-indigo-700">
                  {hit.category}
                </span>
              )}
              {hit.genre && (
                <span className="px-2 py-0.5 rounded-full text-[11px] font-medium bg-purple-50 text-purple-700">
                  {hit.genre}
                </span>
              )}
            </div>
          )}

          {eventDate && (
            <div className="mt-3 text-sm text-gray-500 flex items-center gap-2">
              <span>📅</span>
              <span>{eventDate.toLocaleDateString('en-US', { dateStyle: 'medium' })}</span>
            </div>
          )}

          <div className="mt-4 pt-3 border-t border-gray-50 text-sm font-medium text-blue-600 text-right">
            View tickets →
          </div>
        </div>
      </div>
    </Link>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

const PAGE_SIZE = 18;

export function EventListPage() {
  // Raw input — drives autocomplete via debounce.
  const [input, setInput] = useState('');
  // Committed query (Enter / suggestion click) — drives the result grid.
  const [committed, setCommitted] = useState('');
  // Optional category facet filter — populated from a select.
  const [category, setCategory] = useState('');
  const [page, setPage] = useState(0);
  // Whether the autocomplete dropdown is visible.
  const [open, setOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();

  const debouncedInput = useDebounced(input, 200);
  const showSuggestions = open && debouncedInput.trim().length >= 2;

  // ── Autocomplete (only fires on debounced input, when ≥2 chars) ──────────
  const { data: suggestions = [] } = useQuery<AutocompleteSuggestion[]>({
    queryKey: ['search-suggest', debouncedInput],
    queryFn: () => searchApi.suggestEvents(debouncedInput, 6),
    enabled: showSuggestions,
    staleTime: 5_000,
  });

  // ── Full search — only fires when user commits (Enter or click). ─────────
  const hasQuery = committed.trim().length > 0;
  const { data: searchPage, isLoading: searching } = useQuery({
    queryKey: ['search-events', committed, category, page],
    queryFn: () =>
      searchApi.searchEvents({
        q: committed,
        category: category || undefined,
        from: page * PAGE_SIZE,
        size: PAGE_SIZE,
      }),
    enabled: hasQuery,
    placeholderData: keepPreviousData,
  });

  // ── No-query mode — fall back to /api/tickets/events (existing endpoint) ─
  const { data: allEvents = [], isLoading: listing, isError } = useQuery({
    queryKey: ['events'],
    queryFn: ticketsApi.listEvents,
    enabled: !hasQuery,
  });

  const openEvents  = allEvents.filter((e) => e.status === 'OPEN');
  const otherEvents = allEvents.filter((e) => e.status !== 'OPEN');

  function commit(text: string) {
    setOpen(false);
    setPage(0);
    setCommitted(text.trim());
  }

  function onSuggestionClick(s: AutocompleteSuggestion) {
    // Cheap UX: clicking a specific suggestion jumps straight to its detail
    // page — the user has clearly identified what they want.
    setOpen(false);
    setInput(s.text);
    navigate(`/events/${s.eventId}`);
  }

  // Clear committed search when the input is fully cleared.
  useEffect(() => {
    if (input === '') setCommitted('');
  }, [input]);

  const totalPages = searchPage ? Math.ceil(searchPage.totalHits / PAGE_SIZE) : 0;

  return (
    <div className="flex flex-col gap-8">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-start justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">Events</h1>
          <p className="text-gray-500 mt-1">
            Search by name, artist, venue, or city — typos welcome.
          </p>
        </div>

        <div className="flex items-stretch gap-2 w-full sm:w-auto">
          <select
            value={category}
            onChange={(e) => { setCategory(e.target.value); setPage(0); }}
            className="px-3 py-2 rounded-xl border border-gray-200 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            <option value="">All categories</option>
            <option value="CONCERT">Concert</option>
            <option value="SPORTS">Sports</option>
            <option value="THEATER">Theater</option>
            <option value="CONFERENCE">Conference</option>
          </select>

          <div className="relative w-full sm:w-72">
            <input
              ref={inputRef}
              type="search"
              placeholder="Search events…"
              value={input}
              onChange={(e) => { setInput(e.target.value); setOpen(true); }}
              onFocus={() => setOpen(true)}
              onBlur={() => setTimeout(() => setOpen(false), 150)}
              onKeyDown={(e) => { if (e.key === 'Enter') commit(input); }}
              className="w-full pl-9 pr-3 py-2 rounded-xl border border-gray-200 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <span className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400">🔎</span>

            {/* Autocomplete dropdown */}
            {showSuggestions && suggestions.length > 0 && (
              <ul className="absolute z-10 mt-1 w-full bg-white rounded-xl border border-gray-100 shadow-lg overflow-hidden">
                {suggestions.map((s) => (
                  <li
                    key={s.eventId}
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={() => onSuggestionClick(s)}
                    className="px-3 py-2 hover:bg-blue-50 cursor-pointer text-sm"
                  >
                    <div className="font-medium text-gray-900 truncate">{s.text}</div>
                    {s.primaryArtist && (
                      <div className="text-xs text-gray-500 truncate">{s.primaryArtist}</div>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </div>

      {/* ── No-query mode ─────────────────────────────────────────────────── */}
      {!hasQuery && (
        <>
          {listing && <PageSpinner />}
          {isError && (
            <div className="text-center py-16 text-red-500">Failed to load events. Please try again.</div>
          )}

          {!listing && !isError && (
            <>
              {openEvents.length > 0 && (
                <section>
                  <h2 className="text-lg font-semibold text-gray-800 mb-4 flex items-center gap-2">
                    <span className="w-2 h-2 bg-green-500 rounded-full inline-block"></span>
                    On sale now
                  </h2>
                  <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                    {openEvents.map((e) => <EventCard key={e.id} event={e} />)}
                  </div>
                </section>
              )}

              {otherEvents.length > 0 && (
                <section>
                  <h2 className="text-lg font-semibold text-gray-800 mb-4 text-gray-400">Other events</h2>
                  <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                    {otherEvents.map((e) => <EventCard key={e.id} event={e} />)}
                  </div>
                </section>
              )}

              {allEvents.length === 0 && (
                <div className="text-center py-20 text-gray-400">
                  <p className="text-5xl mb-4">🎭</p>
                  <p className="text-lg font-medium">No events yet</p>
                  <p className="text-sm mt-1">Check back soon</p>
                </div>
              )}
            </>
          )}
        </>
      )}

      {/* ── Search-results mode ───────────────────────────────────────────── */}
      {hasQuery && (
        <>
          <div className="text-sm text-gray-500">
            {searching && !searchPage ? (
              <PageSpinner />
            ) : searchPage ? (
              <span>
                <b className="text-gray-900">{searchPage.totalHits}</b>{' '}
                result{searchPage.totalHits !== 1 ? 's' : ''} for{' '}
                <span className="font-medium text-gray-900">“{committed}”</span>
                {category && <> in <span className="font-medium text-gray-900">{category}</span></>}
              </span>
            ) : null}
          </div>

          {searchPage && searchPage.hits.length > 0 && (
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
              {searchPage.hits.map((h) => <SearchResultCard key={h.id} hit={h} />)}
            </div>
          )}

          {searchPage && searchPage.totalHits === 0 && (
            <div className="text-center py-20 text-gray-400">
              <p className="text-5xl mb-4">🤷</p>
              <p className="text-lg font-medium">No matches</p>
              <p className="text-sm mt-1">Try a different spelling — typos are tolerated automatically.</p>
            </div>
          )}

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="flex items-center justify-center gap-2 pt-2">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="px-3 py-1.5 rounded-lg border border-gray-200 text-sm disabled:opacity-40"
              >
                ← Prev
              </button>
              <span className="text-sm text-gray-500">
                Page {page + 1} of {totalPages}
              </span>
              <button
                onClick={() => setPage((p) => (p + 1 < totalPages ? p + 1 : p))}
                disabled={page + 1 >= totalPages}
                className="px-3 py-1.5 rounded-lg border border-gray-200 text-sm disabled:opacity-40"
              >
                Next →
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
