#!/usr/bin/env node
// ---------------------------------------------------------------------------
// Overture realistic data seeder (snapshot-hybrid).
//
//   node tools/seed/seed.mjs [--scale=smoke|small|medium|large]
//
// Catalog (users, events, tickets, price rules) is created through the REST
// write path so outbox → Kafka → Elasticsearch stay consistent (events show up
// in search automatically). Transactional history (orders, payments, ticket
// confirmations) is bulk-inserted via psql with consistent terminal states and
// created_at spread over the last ~60 days — running thousands of orders through
// the live saga would be far too slow.
//
// Requires: the stack running (nginx on :80), Node 20+, docker CLI.
// Uses data/catalog-snapshot.json if present (from fetch-snapshot.sh), else
// generates a synthetic-but-realistic catalog.
// ---------------------------------------------------------------------------
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { generateSnapshot } from './lib/catalog.mjs';

const __dir = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.SEED_BASE_URL || 'http://localhost';
const PG = 'postgres-master';
const ADMIN_ID = 'admin-000-000-000-000000000001';
const PASSWORD = 'Test1234!';

const SCALES = {
  smoke:  { users: 6,   owners: 2, support: 1, events: 3,   orders: 15 },
  small:  { users: 15,  owners: 3, support: 2, events: 10,  orders: 60 },
  medium: { users: 50,  owners: 6, support: 3, events: 40,  orders: 300 },
  large:  { users: 500, owners: 6, support: 3, events: 150, orders: 3000 },
};
const scaleArg = (process.argv.find(a => a.startsWith('--scale=')) || '').split('=')[1] || 'medium';
const CFG = SCALES[scaleArg];
if (!CFG) { console.error(`Unknown scale "${scaleArg}". Use: ${Object.keys(SCALES).join(', ')}`); process.exit(1); }

// Ticket layout per event (3 sections). Kept modest so large scale stays sane.
const SECTIONS = [
  { name: 'VIP',      rowStart: 'A', rowEnd: 'B', seatStart: 1, seatEnd: 10, priceFrac: 1.0 },  // 20
  { name: 'Standard', rowStart: 'A', rowEnd: 'E', seatStart: 1, seatEnd: 20, priceFrac: 0.6 },  // 100
  { name: 'Economy',  rowStart: 'A', rowEnd: 'J', seatStart: 1, seatEnd: 20, priceFrac: 0.35 }, // 200
];

// ── low-level helpers ───────────────────────────────────────────────────────
const log = (...a) => console.log(...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function api(method, path, { token, body, retries = 4 } = {}) {
  let lastErr;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const res = await fetch(BASE + path, {
        method,
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
      });
      const text = await res.text();
      let json; try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text }; }
      // Retry transient upstream errors (502/503/504) — the CPU-starved services
      // intermittently drop connections under concurrency + BCrypt load.
      if ([502, 503, 504].includes(res.status) && attempt < retries) {
        await sleep(300 * (attempt + 1)); continue;
      }
      return { status: res.status, json };
    } catch (e) {
      lastErr = e;
      if (attempt < retries) { await sleep(300 * (attempt + 1)); continue; }
    }
  }
  return { status: 0, json: { error: String(lastErr) } };
}

function psql(db, sql) {
  return execFileSync('docker', ['exec', '-i', PG, 'psql', '-U', 'ticketing', '-d', db,
    '-v', 'ON_ERROR_STOP=1', '-qtA', '-f', '-'], { input: sql, encoding: 'utf8' });
}

// Run async fn over items with a concurrency cap.
async function pool(items, size, fn) {
  const out = []; let i = 0;
  async function worker() { while (i < items.length) { const idx = i++; out[idx] = await fn(items[idx], idx); } }
  await Promise.all(Array.from({ length: Math.min(size, items.length) }, worker));
  return out;
}

const sqlStr = (v) => v === null || v === undefined ? 'NULL' : `'${String(v).replace(/'/g, "''")}'`;
const pad = (n, w) => String(n).padStart(w, '0');

// ── phase I.2 — users + roles ────────────────────────────────────────────────
async function seedUsers() {
  log(`\n[I.2] Users — registering ${CFG.users} (${CFG.owners} EVENT_OWNER, ${CFG.support} SUPPORT)`);
  const users = Array.from({ length: CFG.users }, (_, i) => ({
    username: `user${pad(i + 1, 4)}`,
    email: `user${pad(i + 1, 4)}@demo.io`,
  }));

  let created = 0, existing = 0, failed = 0;
  // Low concurrency: each register runs BCrypt(12); on a constrained host too
  // many in parallel starve the CPU and time out at the gateway.
  await pool(users, 4, async (u) => {
    const { status } = await api('POST', '/api/auth/register', { body: { ...u, password: PASSWORD } });
    if (status === 201 || status === 200) created++;
    else if (status === 409) existing++;
    else failed++;
  });
  log(`      registered=${created} already-existed=${existing} failed=${failed}`);

  // Promote roles by direct SQL (register hardcodes USER).
  const owners = users.slice(0, CFG.owners).map(u => u.username);
  const support = users.slice(CFG.owners, CFG.owners + CFG.support).map(u => u.username);
  psql('auth_db', `UPDATE users SET role='EVENT_OWNER' WHERE username IN (${owners.map(sqlStr).join(',')});`);
  psql('auth_db', `UPDATE users SET role='SUPPORT'     WHERE username IN (${support.map(sqlStr).join(',')});`);
  log(`      promoted owners=[${owners.join(', ')}] support=[${support.join(', ')}]`);

  return { owners, support, allUsernames: users.map(u => u.username) };
}

async function login(username) {
  const { json } = await api('POST', '/api/auth/login', { body: { emailOrUsername: username, password: PASSWORD } });
  if (!json?.success) throw new Error(`login failed for ${username}: ${JSON.stringify(json)}`);
  return { token: json.data.accessToken, userId: json.data.userId };
}

// ── phase I.3 — catalog via REST ─────────────────────────────────────────────
async function seedCatalog(ownerUsernames) {
  log(`\n[I.3] Catalog — ${CFG.events} events via REST (owner-distributed, ticketed, priced)`);
  const snapshotPath = join(__dir, 'data', 'catalog-snapshot.json');
  let snapshot;
  if (existsSync(snapshotPath)) {
    snapshot = JSON.parse(readFileSync(snapshotPath, 'utf8'));
    log(`      using real snapshot: ${snapshotPath} (${snapshot.length} events)`);
  } else {
    snapshot = generateSnapshot(CFG.events);
    log(`      no snapshot file — generated ${snapshot.length} synthetic events`);
  }
  snapshot = snapshot.slice(0, CFG.events);

  // Log each owner in.
  const owners = [];
  for (const u of ownerUsernames) owners.push({ username: u, ...(await login(u)) });

  let ok = 0, tickets = 0;
  await pool(snapshot, 6, async (ev, idx) => {
    const owner = owners[idx % owners.length];
    const salesOpenAt = new Date().toISOString();
    const salesCloseAt = ev.eventDate;
    // 1) create
    const { status, json } = await api('POST', '/api/tickets/events', {
      token: owner.token,
      body: {
        name: ev.name, salesOpenAt, salesCloseAt, eventDate: ev.eventDate,
        primaryArtist: ev.primaryArtist, venueName: ev.venueName, venueCity: ev.venueCity,
        shortDescription: `${ev.primaryArtist} at ${ev.venueName}`,
        category: ev.category, genre: ev.genre,
      },
    });
    if (status !== 201 && status !== 200) return;
    const eventId = json.data.eventId;
    // 2) open (so it indexes into search + is buyable)
    await api('PATCH', `/api/tickets/events/${eventId}/open`, { token: owner.token });
    // 3) tickets — one batch per section
    for (const s of SECTIONS) {
      const face = Math.round(ev.priceMin + (ev.priceMax - ev.priceMin) * s.priceFrac);
      const r = await api('POST', '/api/tickets/batch', {
        token: owner.token,
        body: {
          eventId, eventName: ev.name, section: s.name,
          rowStart: s.rowStart, rowEnd: s.rowEnd, seatStart: s.seatStart, seatEnd: s.seatEnd,
          facePrice: face,
        },
      });
      if (r.status === 200 || r.status === 201) {
        const rows = (s.rowEnd.charCodeAt(0) - s.rowStart.charCodeAt(0) + 1) * (s.seatEnd - s.seatStart + 1);
        tickets += rows;
      }
    }
    // 4) price rule
    await api('POST', '/api/pricing/rules', {
      token: owner.token,
      body: { eventId, eventName: ev.name, maxSurge: 1.8, totalTickets: 320, eventDate: ev.eventDate },
    });
    ok++;
  });
  log(`      events created=${ok}  (~${tickets} tickets, price rules)`);
  return ok;
}

// ── phase I.4 — transactional history via bulk SQL ───────────────────────────
async function seedTransactions() {
  log(`\n[I.4] Transactions — ${CFG.orders} orders (bulk SQL, consistent states, 60d spread)`);

  // AVAILABLE tickets to consume.
  const tRows = psql('ticket_db',
    `SELECT id||'|'||event_id||'|'||face_price FROM tickets WHERE status='AVAILABLE' ORDER BY random() LIMIT ${CFG.orders};`)
    .trim().split('\n').filter(Boolean).map(l => { const [id, eventId, face] = l.split('|'); return { id, eventId, face: Number(face) }; });

  const userRows = psql('auth_db', `SELECT id FROM users WHERE role='USER';`).trim().split('\n').filter(Boolean);
  if (tRows.length === 0 || userRows.length === 0) { log('      no AVAILABLE tickets or users — skipping'); return; }

  const uuid = () => randomUUID();
  const now = Date.now();
  const pickUser = () => userRows[Math.floor(Math.random() * userRows.length)];

  const orders = [], payments = [], ticketUpdates = [];
  let nC = 0, nF = 0, nP = 0;
  for (const t of tRows) {
    const orderId = uuid(), sagaId = uuid(), userId = pickUser();
    const surge = 1 + Math.random() * 0.5;
    const finalPrice = +(t.face * surge).toFixed(2);
    const createdAt = new Date(now - Math.floor(Math.random() * 60) * 86400000).toISOString();
    const ref = 'seed_' + orderId.slice(0, 12);
    const roll = Math.random();
    let status;
    if (roll < 0.6) status = 'CONFIRMED'; else if (roll < 0.85) status = 'FAILED'; else status = 'PENDING';

    if (status === 'CONFIRMED') {
      orders.push(`(${sqlStr(orderId)},${sqlStr(userId)},${sqlStr(t.id)},${sqlStr(sagaId)},'CONFIRMED',${t.face},${finalPrice},${sqlStr(ref)},NULL,0,${sqlStr(createdAt)},${sqlStr(createdAt)})`);
      payments.push(`(${sqlStr(userId)},${sqlStr(orderId)},${sqlStr(t.id)},${finalPrice},'SUCCESS',${sqlStr(ref)},1,${sqlStr(sagaId)},${sqlStr(createdAt)})`);
      ticketUpdates.push(`('${t.id}','${orderId}','${userId}',${finalPrice},'${createdAt}')`);
      nC++;
    } else if (status === 'FAILED') {
      orders.push(`(${sqlStr(orderId)},${sqlStr(userId)},${sqlStr(t.id)},${sqlStr(sagaId)},'FAILED',${t.face},NULL,NULL,${sqlStr('Payment declined')},0,${sqlStr(createdAt)},${sqlStr(createdAt)})`);
      payments.push(`(${sqlStr(userId)},${sqlStr(orderId)},${sqlStr(t.id)},${finalPrice},'FAILED',${sqlStr(ref)},3,${sqlStr(sagaId)},${sqlStr(createdAt)})`);
      nF++;
    } else {
      orders.push(`(${sqlStr(orderId)},${sqlStr(userId)},${sqlStr(t.id)},${sqlStr(sagaId)},'PENDING',${t.face},NULL,NULL,NULL,0,${sqlStr(createdAt)},${sqlStr(createdAt)})`);
      nP++;
    }
  }

  // Insert orders.
  psql('order_db', `INSERT INTO orders (id,user_id,ticket_id,saga_id,status,requested_price,final_price,payment_reference,failure_reason,version,created_at,updated_at) VALUES\n${orders.join(',\n')};`);
  // Insert payments.
  if (payments.length)
    psql('payment_db', `INSERT INTO payments (user_id,order_id,ticket_id,amount,status,payment_reference,attempt_count,saga_id,created_at) VALUES\n${payments.join(',\n')};`);
  // Flip CONFIRMED tickets (consistent with the stress-test invariant).
  if (ticketUpdates.length)
    psql('ticket_db',
      `UPDATE tickets t SET status='CONFIRMED', locked_by_order_id=v.oid, locked_by_user_id=v.uid, locked_price=v.price, confirmed_at=v.ts::timestamptz
       FROM (VALUES\n${ticketUpdates.join(',\n')}) AS v(tid,oid,uid,price,ts) WHERE t.id=v.tid;`);

  log(`      orders: CONFIRMED=${nC} FAILED=${nF} PENDING=${nP}  payments=${payments.length}  tickets-confirmed=${ticketUpdates.length}`);
}

// ── phase I.5 — verify ───────────────────────────────────────────────────────
function verify() {
  log(`\n[I.5] Verify`);
  const roleDist = psql('auth_db', `SELECT role||'='||COUNT(*) FROM users GROUP BY role ORDER BY role;`).trim().replace(/\n/g, '  ');
  log(`      users:   ${roleDist}`);
  const evt = psql('ticket_db', `SELECT COUNT(*) FROM events;`).trim();
  const tk = psql('ticket_db', `SELECT status||'='||COUNT(*) FROM tickets GROUP BY status ORDER BY status;`).trim().replace(/\n/g, '  ');
  log(`      events:  ${evt}`);
  log(`      tickets: ${tk}`);
  const ord = psql('order_db', `SELECT status||'='||COUNT(*) FROM orders GROUP BY status ORDER BY status;`).trim().replace(/\n/g, '  ');
  log(`      orders:  ${ord}`);
  const pay = psql('payment_db', `SELECT status||'='||COUNT(*) FROM payments GROUP BY status ORDER BY status;`).trim().replace(/\n/g, '  ');
  log(`      payments:${pay}`);
  // cross-DB invariant: every CONFIRMED order's ticket is CONFIRMED+locked to it
  const mism = psql('order_db', `SELECT COUNT(*) FROM orders WHERE status='CONFIRMED';`).trim();
  log(`      (CONFIRMED orders=${mism}; ticket confirmations should match)`);
}

// ── main ─────────────────────────────────────────────────────────────────────
(async () => {
  log(`Overture seeder — scale=${scaleArg} → ${JSON.stringify(CFG)}`);
  // sanity: stack reachable
  const h = await api('GET', '/api/tickets/events?page=0&size=1');
  if (h.status >= 500) { console.error(`Stack not reachable at ${BASE} (status ${h.status})`); process.exit(1); }

  const { owners } = await seedUsers();
  await seedCatalog(owners);
  await seedTransactions();
  verify();
  log(`\nDone.`);
})().catch(e => { console.error('SEED FAILED:', e); process.exit(1); });
