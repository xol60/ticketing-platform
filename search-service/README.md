# Search Service

**Port:** 8091

Read-only Elasticsearch-backed event search. Lives outside the order saga —
Postgres (in `ticket-service`) is the source of truth, this service maintains
a derived ES index of `OPEN` events synced over the `event.search.indexed`
Kafka topic.

If ES or this service is down, ticket-service keeps serving the canonical
event-detail page; only the search box degrades.

---

## Responsibilities

- Maintain the `events` Elasticsearch index in sync with ticket-service's
  source of truth (consume `event.search.indexed` → upsert OPEN events,
  delete anything else).
- Serve two public search endpoints with Caffeine-cached query results.
- Stay decoupled from the order saga so its outages don't propagate.

---

## Endpoints

| Method | Path                                            | Auth | Description                                              |
| ------ | ----------------------------------------------- | ---- | -------------------------------------------------------- |
| `GET`  | `/api/search/events?q=&category=&genre=&from=&size=` | No  | Multi-field BM25 search with optional facet filters     |
| `GET`  | `/api/search/events/suggest?q=&size=`           | No   | Edge-ngram autocomplete from 2-character prefix         |

Both are public (no auth required) — exposed by the gateway via the
`publicGetPaths` config. `status = OPEN` is enforced as a hard filter at
the query layer too, as defence-in-depth.

---

## Indexing pipeline

```
ticket-service              Kafka                 search-service        Elasticsearch
─────────────────           ─────────────────     ──────────────────    ──────────────
EventService                event.search.indexed  EventIndexConsumer    events index
  ├─ createEvent      ──▶   (10 partitions,       ├─ OPEN  → save()  ──▶ PUT _doc/{id}
  ├─ updateEvent      ──▶    keyed by eventId)    └─ else  → delete()──▶ DELETE _doc/{id}
  └─ changeStatus     ──▶
```

Manual-ack Kafka consumer + idempotent `save()` / `deleteById()` make redelivery
safe. Keying by `eventId` keeps per-event updates serialized within a partition —
no risk of a stale upsert overtaking a later delete.

---

## Three features that justify Elasticsearch

| Feature                       | Mechanism                                                                                   |
| ----------------------------- | ------------------------------------------------------------------------------------------- |
| Multi-field full-text + boost | `multi_match` over six fields: `name^3.0`, `primaryArtist^2.5`, `venueName^1.5`, `venueCity^1.0`, `shortDescription^0.8`, `fullDescription^0.4` |
| Fuzzy matching                | `fuzziness=AUTO` — 1 edit for 3-5 chars, 2 edits for 6+ chars (e.g. `coldlpay` → Coldplay)  |
| Autocomplete-as-you-type      | `name.edge` sub-field with custom `edge_ngram_analyzer` (min_gram=2, max_gram=20)           |

---

## Load-shaping for /suggest

Autocomplete is naturally bursty: a single typist fires several requests per
second, and many users hit hot prefixes (`co`, `col`, …) simultaneously. Four
layers cooperate to keep Elasticsearch from being hammered.

| Layer | Mechanism | Effect |
|-------|-----------|--------|
| UI    | 300 ms debounce + `AbortSignal` cancellation on each new keystroke | Stops "c → co → col" from leaving three parallel ES queries open |
| UI    | React Query `staleTime: 60 s` | Back-spacing and re-typing the same prefix is a free in-memory hit |
| Gateway | Per-path rate-limit override (60 r/s vs the default 20 r/s) | Autocomplete-friendly while still well below nginx's 200 r/s per-IP |
| Service | Caffeine `search-suggest` cache (60 s TTL, 50 000 entries, W-TinyLFU eviction) | Repeat queries for the same prefix never reach ES |

Two SpEL guards keep the server cache clean:

- `key = normalise(prefix)` — lowercases + trims + collapses whitespace, so
  `"Co"`, `"co"`, `"  CO  "` all share one entry.
- `condition = cacheable(prefix)` — admission filter rejecting prefixes that
  are too short, too long, or have no letters. Pure-digit / pure-punctuation
  inputs bypass the cache entirely so they can't pollute it.

`EventIndexConsumer` calls `cache.invalidate()` after every successful ES
write — so users see edits within Kafka latency (~1-2 s), not after the
60 s TTL.

**Measured outcome:** in a 30-call replay across 6 hot prefixes, only **1**
request reached Elasticsearch (~30× reduction).

The full search endpoint (`/api/search/events`) is deliberately **not** cached.
Free-text queries plus paging + facets give every key high cardinality and
low reuse; caching them would pollute the table without measurably reducing
load. Gateway rate-limit + client debounce are sufficient there.

---

## Internal architecture

```
SearchController
      │
EventSearchService    ◀── @Cacheable("search-suggest") on suggest()
      │
ElasticsearchOperations
      │
Elasticsearch (events index)

EventIndexConsumer    ◀── Kafka: event.search.indexed
      │
EventSearchRepository
      │
Elasticsearch (events index)
      │
CacheManager.getCache("search-suggest").invalidate()   ◀── after each write
```

---

## Configuration

```yaml
# application.yml
elasticsearch:
  url: ${ELASTICSEARCH_URL:http://localhost:9200}

spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:29092}
  autoconfigure:
    exclude:
      # search-service has no relational DB
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
```

---

## Explicitly out of scope

- Geo "near me" / radius queries (needs lat/lng and map provider)
- Multilingual / Vietnamese tokenisation (needs ICU analyzer + synonyms)
- Popularity / click-through-rate ranking (needs separate event-tracking)
- Aggregations / faceted counts (basic filter params only)
- Bulk reindex job — deferred. If the index drifts from Postgres, the
  ticket-service can republish every event on `event.search.indexed` to
  trigger a full rebuild from the consumer side.

---

## Dependencies

- **Elasticsearch** 8.13 (single-node, security disabled in dev compose)
- **Kafka** — consumes `event.search.indexed`
- **common-lib** — for `EventSearchIndexedEvent` payload type and `EventStatus` enum
