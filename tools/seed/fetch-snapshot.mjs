#!/usr/bin/env node
// ---------------------------------------------------------------------------
// One-time snapshot fetch → tools/seed/data/catalog-snapshot.json
//
//   TM_API_KEY=xxxx      node tools/seed/fetch-snapshot.mjs [--count=200]
//   SEATGEEK_CLIENT_ID=x node tools/seed/fetch-snapshot.mjs --source=seatgeek
//   node tools/seed/fetch-snapshot.mjs --synthetic     # offline, no key
//
// The committed JSON is what seed.mjs consumes, so seeding itself never calls
// the network. Normalized shape (matches lib/catalog.mjs output):
//   { name, primaryArtist, venueName, venueCity, category, genre,
//     eventDate (ISO), priceMin, priceMax }
// ---------------------------------------------------------------------------
import { writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { generateSnapshot } from './lib/catalog.mjs';

const __dir = dirname(fileURLToPath(import.meta.url));
const OUT = join(__dir, 'data', 'catalog-snapshot.json');
const arg = (k, d) => { const m = process.argv.find(a => a.startsWith(`--${k}=`)); return m ? m.split('=')[1] : d; };
const has = (k) => process.argv.includes(`--${k}`);
const COUNT = Number(arg('count', 200));

const CATEGORY_MAP = { Music: 'CONCERT', Sports: 'SPORTS', 'Arts & Theatre': 'THEATER', Miscellaneous: 'OTHER' };

async function fromTicketmaster(key) {
  const events = [];
  const pages = Math.ceil(COUNT / 200);
  for (let p = 0; p < pages && events.length < COUNT; p++) {
    const url = `https://app.ticketmaster.com/discovery/v2/events.json?apikey=${key}&size=200&page=${p}&sort=date,asc`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`Ticketmaster ${res.status}: ${await res.text()}`);
    const data = await res.json();
    for (const e of data._embedded?.events ?? []) {
      const venue = e._embedded?.venues?.[0];
      const att = e._embedded?.attractions?.[0];
      const cls = e.classifications?.[0];
      const pr = e.priceRanges?.[0];
      events.push({
        name: e.name,
        primaryArtist: att?.name ?? e.name,
        venueName: venue?.name ?? 'Unknown Venue',
        venueCity: venue?.city?.name ?? 'Unknown',
        category: CATEGORY_MAP[cls?.segment?.name] ?? 'OTHER',
        genre: (cls?.genre?.name ?? 'OTHER').toUpperCase(),
        eventDate: e.dates?.start?.dateTime ?? new Date(Date.now() + 30 * 86400000).toISOString(),
        priceMin: Math.round(pr?.min ?? 50),
        priceMax: Math.round(pr?.max ?? 250),
      });
    }
  }
  return events.slice(0, COUNT);
}

async function fromSeatGeek(clientId) {
  const events = [];
  const perPage = 100;
  const pages = Math.ceil(COUNT / perPage);
  for (let p = 1; p <= pages && events.length < COUNT; p++) {
    const url = `https://api.seatgeek.com/2/events?client_id=${clientId}&per_page=${perPage}&page=${p}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`SeatGeek ${res.status}: ${await res.text()}`);
    const data = await res.json();
    for (const e of data.events ?? []) {
      events.push({
        name: e.title,
        primaryArtist: e.performers?.[0]?.name ?? e.title,
        venueName: e.venue?.name ?? 'Unknown Venue',
        venueCity: e.venue?.city ?? 'Unknown',
        category: (e.type || 'OTHER').toUpperCase(),
        genre: (e.performers?.[0]?.genres?.[0]?.name ?? 'OTHER').toUpperCase(),
        eventDate: e.datetime_utc ? new Date(e.datetime_utc).toISOString() : new Date(Date.now() + 30 * 86400000).toISOString(),
        priceMin: Math.round(e.stats?.lowest_price ?? 50),
        priceMax: Math.round(e.stats?.highest_price ?? 250),
      });
    }
  }
  return events.slice(0, COUNT);
}

(async () => {
  mkdirSync(dirname(OUT), { recursive: true });
  let events, source;
  const source_arg = arg('source', 'ticketmaster');

  if (has('synthetic')) {
    events = generateSnapshot(COUNT); source = 'synthetic';
  } else if (source_arg === 'seatgeek' && process.env.SEATGEEK_CLIENT_ID) {
    events = await fromSeatGeek(process.env.SEATGEEK_CLIENT_ID); source = 'seatgeek';
  } else if (process.env.TM_API_KEY) {
    events = await fromTicketmaster(process.env.TM_API_KEY); source = 'ticketmaster';
  } else {
    console.warn('No TM_API_KEY / SEATGEEK_CLIENT_ID and no --synthetic — falling back to synthetic.');
    events = generateSnapshot(COUNT); source = 'synthetic';
  }

  writeFileSync(OUT, JSON.stringify(events, null, 2));
  console.log(`Wrote ${events.length} events (${source}) → ${OUT}`);
})().catch(e => { console.error('FETCH FAILED:', e.message); process.exit(1); });
