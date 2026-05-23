-- ============================================================================
--  Demo-data seed for the Overture ticketing platform
-- ============================================================================
--
--  Creates one demo client (user) plus a curated set of events with realistic
--  searchable metadata, and ~30 tickets per OPEN event. Idempotent: safe to
--  re-run — uses ON CONFLICT DO NOTHING so reseeding just fills in the gaps.
--
--  WHAT THIS COVERS
--    • Auth DB        : 1 demo user  (username "demouser" / password "Test1234!")
--    • Ticket DB      : 12 events spanning CONCERT / SPORTS / THEATER / CONFERENCE
--                       — mostly OPEN with a sprinkle of CANCELLED / DRAFT so
--                       the search-status filter has negative cases to assert
--                       against. Every event has searchable metadata
--                       (primaryArtist, venueName, venueCity, category, genre,
--                        shortDescription) so the multi-field search has signal.
--                       ~30 tickets per OPEN event (2 sections × 3 rows × 5 seats),
--                       face prices varied so the surge banner is visible.
--
--  HOW TO RUN
--
--    Option A — single-shot via the bash wrapper (recommended; also indexes ES):
--
--      bash tests/seed-demo-data.sh
--
--    Option B — pure SQL, run once per database:
--
--      docker exec -i postgres-master \
--          psql -U ticketing -d auth_db   < tests/seed-demo-data.sql
--      docker exec -i postgres-master \
--          psql -U ticketing -d ticket_db < tests/seed-demo-data.sql
--
--    Each invocation runs only the section guarded by `to_regclass()` on a
--    table that exists in that database — see the conditional DO blocks below.
--    The other section becomes a no-op. SQL-only mode does NOT populate the
--    Elasticsearch search index (Kafka publish only fires via the REST path);
--    use Option A or do a one-time replay (PATCH /events/{id}/open) afterwards.
--
--  TEST CREDENTIALS AFTER SEED
--    username : demouser
--    password : Test1234!
--    role     : USER
-- ============================================================================


-- ──────────────────────────────────────────────────────────────────────────────
--  AUTH DB section — creates the demo user
-- ──────────────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF to_regclass('public.users') IS NULL THEN
        RAISE NOTICE 'Skipping auth section: public.users does not exist in this DB';
        RETURN;
    END IF;

    -- Reuse the bcrypt hash of qauser99 (password = Test1234!) so the seed
    -- user has a known login without depending on having BCryptPasswordEncoder
    -- available in psql. The hash already encodes salt + work-factor; copying
    -- it is safe because bcrypt verification re-derives the salt from the hash.
    INSERT INTO users (
        id, email, username, password_hash, role, enabled, email_verified,
        failed_login_attempts, created_at, updated_at
    ) VALUES (
        'demo-0000-0000-0000-000000000001',
        'demo@overture.test',
        'demouser',
        '$2a$12$MXHWOQO7x9Yqe3sdSry8HOnUMrq.LSGfZRS6w.nwzpidMFSyf9T/G',
        'USER',
        true,
        true,
        0,
        now(),
        now()
    )
    ON CONFLICT (username) DO NOTHING;

    RAISE NOTICE 'Auth seed complete — demouser / Test1234!';
END $$;


-- ──────────────────────────────────────────────────────────────────────────────
--  TICKET DB section — creates events + tickets
-- ──────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    -- Parallel arrays — using parallel UNNEST so we can iterate cleanly in
    -- pure PL/pgSQL without defining a composite type. Each column below
    -- corresponds to one event; rows are paired by position.
    ids               text[] := ARRAY[
        'demo-evt-01-0000-0000-000000000001',
        'demo-evt-02-0000-0000-000000000002',
        'demo-evt-03-0000-0000-000000000003',
        'demo-evt-04-0000-0000-000000000004',
        'demo-evt-05-0000-0000-000000000005',
        'demo-evt-06-0000-0000-000000000006',
        'demo-evt-07-0000-0000-000000000007',
        'demo-evt-08-0000-0000-000000000008',
        'demo-evt-09-0000-0000-000000000009',
        'demo-evt-10-0000-0000-000000000010',
        'demo-evt-11-0000-0000-000000000011',
        'demo-evt-12-0000-0000-000000000012'
    ];
    names             text[] := ARRAY[
        'Coldplay World Tour 2026',
        'Taylor Swift Eras Tour',
        'Metallica M72 World Tour',
        'BLACKPINK Born Pink Reunion',
        'Daft Punk Reunion Live',
        'LA Lakers vs Boston Celtics',
        'NY Yankees vs Boston Red Sox',
        'UEFA Champions League Final',
        'Hamilton — Broadway',
        'The Lion King — West End',
        'Adele Live — Las Vegas Residency',
        'Future Conference — DRAFT'
    ];
    artists           text[] := ARRAY[
        'Coldplay','Taylor Swift','Metallica','BLACKPINK','Daft Punk',
        'NBA','MLB','UEFA','Lin-Manuel Miranda','Disney Theatrical',
        'Adele','TBA'
    ];
    venues            text[] := ARRAY[
        'Madison Square Garden','SoFi Stadium','Wembley Stadium','Gocheok Sky Dome','Accor Arena',
        'Crypto.com Arena','Yankee Stadium','Wembley Stadium','Richard Rodgers Theatre','Lyceum Theatre',
        'The Colosseum','TBA'
    ];
    cities            text[] := ARRAY[
        'New York','Los Angeles','London','Seoul','Paris',
        'Los Angeles','New York','London','New York','London',
        'Las Vegas','TBA'
    ];
    categories        text[] := ARRAY[
        'CONCERT','CONCERT','CONCERT','CONCERT','CONCERT',
        'SPORTS','SPORTS','SPORTS','THEATER','THEATER',
        'CONCERT','CONFERENCE'
    ];
    genres            text[] := ARRAY[
        'POP','POP','ROCK','KPOP','EDM',
        'BASKETBALL','BASEBALL','FOOTBALL','MUSICAL','MUSICAL',
        'POP','TECH'
    ];
    descriptions      text[] := ARRAY[
        'Music of the Spheres tour — featuring the full album live',
        'Eras Tour finale night — three hours of the entire discography',
        'M72 stadium tour — two distinct nights, no repeats',
        'Born Pink reunion night with surprise guests',
        'Limited reunion show — pyramid stage, full helmets',
        'Regular-season showdown of two title rivals',
        'Subway-style rivalry in the Bronx',
        'Champions League final — venue confirmed, teams TBD',
        'The Pulitzer-prize-winning American musical',
        'The award-winning Disney stage adaptation',
        'Weekends with Adele — extended residency dates',
        'Internal draft — not yet announced'
    ];
    statuses          text[] := ARRAY[
        'OPEN','OPEN','OPEN','OPEN','OPEN',
        'OPEN','OPEN','OPEN','OPEN','OPEN',
        'CANCELLED','DRAFT'    -- last two intentionally not OPEN for negative cases
    ];
    days_to_event     int[]  := ARRAY[
        42, 60, 28, 80, 35,
        14, 21, 90,  7, 50,
        100, 120
    ];

    -- Loop locals
    n                 int;
    i                 int;
    eid               text;
    ename             text;
    estatus           text;
    section_label     text;
    row_num           int;
    seat_num          int;
    face_price        numeric(10,2);
BEGIN
    IF to_regclass('public.events') IS NULL THEN
        RAISE NOTICE 'Skipping ticket section: public.events does not exist in this DB';
        RETURN;
    END IF;

    n := array_length(ids, 1);

    -- ── Insert events ───────────────────────────────────────────────────
    FOR i IN 1..n LOOP
        INSERT INTO events (
            id, name, status,
            sales_open_at, sales_close_at, event_date,
            primary_artist, venue_name, venue_city,
            short_description, full_description,
            category, genre,
            created_at, updated_at
        ) VALUES (
            ids[i],
            names[i],
            statuses[i],
            now() - interval '1 day',
            now() + make_interval(days => days_to_event[i]),
            now() + make_interval(days => days_to_event[i]),
            artists[i], venues[i], cities[i],
            descriptions[i],
            descriptions[i] || E'\n\nFull description: ' || descriptions[i]
                || ' This is a longer marketing copy used by the full_description searchable field — weight 0.4 in the multi-field query.',
            categories[i], genres[i],
            now(), now()
        )
        ON CONFLICT (id) DO NOTHING;
    END LOOP;

    -- ── Insert ~30 tickets per OPEN event ───────────────────────────────
    -- (2 sections × 3 rows × 5 seats = 30 tickets per event; ~300 total).
    FOR i IN 1..n LOOP
        eid     := ids[i];
        ename   := names[i];
        estatus := statuses[i];
        IF estatus <> 'OPEN' THEN CONTINUE; END IF;

        FOREACH section_label IN ARRAY ARRAY['A','B'] LOOP
            FOR row_num IN 1..3 LOOP
                FOR seat_num IN 1..5 LOOP
                    -- Section A is closer to stage, so pricier. Within a section,
                    -- higher row numbers and seat numbers add a few dollars
                    -- so the UI can show real variation.
                    face_price :=
                        CASE section_label WHEN 'A' THEN 100.00 ELSE 75.00 END
                        + row_num * 10
                        + seat_num * 2;

                    INSERT INTO tickets (
                        id, event_id, event_name,
                        section, row, seat,
                        status, face_price,
                        created_at, updated_at
                    ) VALUES (
                        -- Deterministic id so a rerun matches the same row.
                        'demo-tkt-' || substr(eid, 10, 2)
                            || '-' || section_label
                            || '-' || lpad(row_num::text, 2, '0')
                            || '-' || lpad(seat_num::text, 2, '0')
                            || '-000000000000',
                        eid,
                        ename,
                        section_label,
                        row_num::text,
                        seat_num::text,
                        'AVAILABLE',
                        face_price,
                        now(),
                        now()
                    )
                    ON CONFLICT (id) DO NOTHING;
                END LOOP;
            END LOOP;
        END LOOP;
    END LOOP;

    RAISE NOTICE 'Ticket seed complete: 12 events (10 OPEN, 1 CANCELLED, 1 DRAFT) + 300 tickets';
END $$;


-- ──────────────────────────────────────────────────────────────────────────────
--  BACKFILL: ensure every OPEN event has at least 30 AVAILABLE tickets
-- ──────────────────────────────────────────────────────────────────────────────
--
--  Why this block exists separately from the "demo-evt-" seed above:
--
--    The platform may already contain OPEN events that were created by other
--    test runs (e.g. earlier scenario tests, the stress test, or hand-curl'd
--    admin endpoints) — those events typically have 0 or only a handful of
--    tickets. When the user opens such an event from the SPA they hit
--    /api/tickets/events/{id}/tickets and see an empty grid, which is a poor
--    demo. This block backfills ANY OPEN event whose current
--    AVAILABLE-ticket count is below {min_tickets}, so the entire OPEN list
--    is browse-ready.
--
--  Implementation notes:
--    • Uses "evtfill-" prefix on generated ids so backfill rows are distinct
--      from the deterministic "demo-tkt-" rows above; ON CONFLICT DO NOTHING
--      still keeps re-runs idempotent.
--    • Face prices follow the same A/B-section formula so the surge banner
--      and the orange "strike-through" UI render exactly the same way as for
--      the curated demo events.
-- ──────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    min_tickets       int := 30;  -- target floor per OPEN event
    e                 record;
    section_label     text;
    row_num           int;
    seat_num          int;
    face_price        numeric(10,2);
    short_eid         text;
    backfilled_events int := 0;
    backfilled_tix    int := 0;
BEGIN
    IF to_regclass('public.events') IS NULL THEN
        RAISE NOTICE 'Skipping backfill: public.events does not exist in this DB';
        RETURN;
    END IF;

    FOR e IN
        SELECT ev.id, ev.name,
               COUNT(t.id) FILTER (WHERE t.status = 'AVAILABLE') AS avail_count
          FROM events ev
          LEFT JOIN tickets t ON t.event_id = ev.id
         WHERE ev.status = 'OPEN'
         GROUP BY ev.id, ev.name
        HAVING COUNT(t.id) FILTER (WHERE t.status = 'AVAILABLE') < min_tickets
    LOOP
        backfilled_events := backfilled_events + 1;
        -- Hash the event id into 8 hex chars so generated ticket ids stay
        -- compact AND deterministic across reruns (md5 is fine — we only need
        -- a stable short identifier, not a cryptographic guarantee).
        short_eid := substr(md5(e.id), 1, 8);

        FOREACH section_label IN ARRAY ARRAY['A','B'] LOOP
            FOR row_num IN 1..3 LOOP
                FOR seat_num IN 1..5 LOOP
                    face_price :=
                        CASE section_label WHEN 'A' THEN 100.00 ELSE 75.00 END
                        + row_num * 10
                        + seat_num * 2;

                    -- Broader ON CONFLICT DO NOTHING (without an inference
                    -- column) skips on EITHER kind of conflict:
                    --   • PK on `id` — same backfill rerun
                    --   • Unique (event_id, section, row, seat) NULLS NOT
                    --     DISTINCT — pre-existing event might already have a
                    --     seat at the same coordinates, in which case the
                    --     existing ticket wins and we just skip silently.
                    INSERT INTO tickets (
                        id, event_id, event_name,
                        section, row, seat,
                        status, face_price,
                        created_at, updated_at
                    ) VALUES (
                        'evtfill-' || short_eid
                            || '-' || section_label
                            || '-' || lpad(row_num::text, 2, '0')
                            || '-' || lpad(seat_num::text, 2, '0'),
                        e.id,
                        e.name,
                        section_label,
                        row_num::text,
                        seat_num::text,
                        'AVAILABLE',
                        face_price,
                        now(),
                        now()
                    )
                    ON CONFLICT DO NOTHING;
                    -- We can't easily count "actually inserted vs skipped" here without
                    -- adding a RETURNING + diagnostics roundtrip; the +1 is "rows attempted",
                    -- which is fine for an end-of-run summary.
                    backfilled_tix := backfilled_tix + 1;
                END LOOP;
            END LOOP;
        END LOOP;
    END LOOP;

    IF backfilled_events = 0 THEN
        RAISE NOTICE 'Ticket backfill: every OPEN event already has >= % AVAILABLE tickets — nothing to do', min_tickets;
    ELSE
        RAISE NOTICE 'Ticket backfill: padded % under-stocked OPEN event(s) with up to % tickets each (% attempted, dupes skipped)',
                     backfilled_events, min_tickets, backfilled_tix;
    END IF;
END $$;


-- ──────────────────────────────────────────────────────────────────────────────
--  NORMALISE LEGACY FACE PRICES
-- ──────────────────────────────────────────────────────────────────────────────
--
--  Symptom this fixes: in mixed-tenant demo databases we sometimes see tickets
--  with face_price like 1500000 or 500000 — these look like Vietnamese đồng
--  amounts left behind by other test runs, but the UI labels every price with
--  a "$" and has no per-event currency, so $1,500,000.00 reads as a UI bug
--  rather than a real (if oddly-denominated) price.
--
--  This block rewrites any AVAILABLE ticket with face_price > {legacy_threshold}
--  into the same A/B-section formula used by the demo seed — so the UI shows
--  a consistent $75–$140 range everywhere.
--
--  Only AVAILABLE tickets are touched. RESERVED/CONFIRMED tickets keep their
--  original price because changing them could desync a live saga / order
--  amount that's already been quoted to a user.
-- ──────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    legacy_threshold numeric := 500;   -- USD-shaped demo never exceeds ~$200
    updated_rows     int;
BEGIN
    IF to_regclass('public.tickets') IS NULL THEN
        RAISE NOTICE 'Skipping price normalisation: public.tickets does not exist in this DB';
        RETURN;
    END IF;

    WITH normalised AS (
        UPDATE tickets t
           SET face_price =
                   CASE COALESCE(t.section, 'A') WHEN 'A' THEN 100.00 ELSE 75.00 END
                   -- Derive deterministic row/seat bumps from the existing
                   -- section+row+seat — falling back to 1 if not numeric so
                   -- the UPDATE never throws on free-form seat labels.
                   + COALESCE(NULLIF(regexp_replace(COALESCE(t.row, '1'), '\D', '', 'g'), '')::int, 1) * 10
                   + COALESCE(NULLIF(regexp_replace(t.seat, '\D', '', 'g'), '')::int, 1) * 2,
               updated_at = now()
         WHERE t.face_price > legacy_threshold
           AND t.status = 'AVAILABLE'
         RETURNING 1
    )
    SELECT COUNT(*) INTO updated_rows FROM normalised;

    IF updated_rows = 0 THEN
        RAISE NOTICE 'Price normalisation: no AVAILABLE ticket exceeds $% — nothing to rewrite', legacy_threshold;
    ELSE
        RAISE NOTICE 'Price normalisation: rewrote % AVAILABLE ticket(s) priced above $% into demo range', updated_rows, legacy_threshold;
    END IF;
END $$;
