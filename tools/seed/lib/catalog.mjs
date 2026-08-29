// Curated real-world artists / venues / shows used to synthesize a realistic
// event catalog snapshot when no live Ticketmaster/SeatGeek key is available.
// The shape produced here matches what fetch-snapshot.sh writes from the real
// APIs, so seed.mjs consumes either source identically.

export const ARTISTS = {
  CONCERT: [
    ['Taylor Swift', 'POP'], ['Coldplay', 'ROCK'], ['BLACKPINK', 'KPOP'],
    ['Ed Sheeran', 'POP'], ['The Weeknd', 'POP'], ['BTS', 'KPOP'],
    ['Adele', 'POP'], ['Bruno Mars', 'POP'], ['Billie Eilish', 'POP'],
    ['Drake', 'HIPHOP'], ['Imagine Dragons', 'ROCK'], ['Dua Lipa', 'POP'],
    ['Post Malone', 'HIPHOP'], ['Metallica', 'ROCK'], ['David Guetta', 'EDM'],
    ['Calvin Harris', 'EDM'], ['Sơn Tùng M-TP', 'VPOP'], ['Mỹ Tâm', 'VPOP'],
    ['Hà Anh Tuấn', 'VPOP'], ['Đen Vâu', 'VPOP'], ['Charlie Puth', 'POP'],
    ['Maroon 5', 'ROCK'], ['Twice', 'KPOP'], ['SEVENTEEN', 'KPOP'],
  ],
  SPORTS: [
    ['Manchester United vs Liverpool', 'FOOTBALL'],
    ['Lakers vs Warriors', 'BASKETBALL'], ['El Clásico', 'FOOTBALL'],
    ['Wimbledon Finals', 'TENNIS'], ['Super Bowl LX', 'FOOTBALL'],
    ['Formula 1 Grand Prix', 'MOTORSPORT'], ['NBA All-Star Game', 'BASKETBALL'],
    ['UFC Championship Night', 'MMA'], ['Vietnam vs Thailand', 'FOOTBALL'],
  ],
  THEATER: [
    ['Hamilton', 'MUSICAL'], ['The Phantom of the Opera', 'MUSICAL'],
    ['The Lion King', 'MUSICAL'], ['Les Misérables', 'MUSICAL'],
    ['Wicked', 'MUSICAL'], ['Swan Lake', 'BALLET'], ['The Nutcracker', 'BALLET'],
  ],
  CONFERENCE: [
    ['AWS re:Invent', 'TECH'], ['Google I/O', 'TECH'], ['Web Summit', 'TECH'],
    ['TEDx Global', 'IDEAS'], ['Money20/20', 'FINTECH'], ['CES Keynote', 'TECH'],
  ],
};

export const VENUES = [
  ['Madison Square Garden', 'New York'], ['The O2 Arena', 'London'],
  ['SVĐ Mỹ Đình', 'Hà Nội'], ['Marina Bay Sands', 'Singapore'],
  ['Tokyo Dome', 'Tokyo'], ['Staples Center', 'Los Angeles'],
  ['Sydney Opera House', 'Sydney'], ['Accor Arena', 'Paris'],
  ['Allianz Arena', 'Munich'], ['Camp Nou', 'Barcelona'],
  ['SVĐ Quốc gia', 'TP. Hồ Chí Minh'], ['Wembley Stadium', 'London'],
  ['Rogers Centre', 'Toronto'], ['Suntec Convention Centre', 'Singapore'],
];

// Deterministic PRNG (mulberry32) so a given seed reproduces the same catalog.
export function rng(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const pick = (rand, arr) => arr[Math.floor(rand() * arr.length)];

// Base price band per category (VND-ish demo numbers), scaled per section later.
const PRICE_BAND = {
  CONCERT: [400, 2500], SPORTS: [300, 1800],
  THEATER: [500, 2000], CONFERENCE: [200, 900],
};

/**
 * Produce `count` synthetic-but-realistic events. Each carries the same fields
 * fetch-snapshot.sh emits: name, eventDate (ISO), venueName, venueCity,
 * primaryArtist, category, genre, priceMin, priceMax.
 */
export function generateSnapshot(count, seed = 42) {
  const rand = rng(seed);
  const categories = Object.keys(ARTISTS);
  const now = Date.now();
  const events = [];

  for (let i = 0; i < count; i++) {
    const category = pick(rand, categories);
    const [artist, genre] = pick(rand, ARTISTS[category]);
    const [venueName, venueCity] = pick(rand, VENUES);
    // Event date 10–400 days out.
    const eventDate = new Date(now + (10 + Math.floor(rand() * 390)) * 86400000);
    const [lo, hi] = PRICE_BAND[category];
    const priceMin = lo + Math.floor(rand() * 100);
    const priceMax = hi - Math.floor(rand() * 300);

    const year = eventDate.getFullYear();
    const suffix = category === 'CONCERT' ? ' Live' :
                   category === 'SPORTS' ? '' :
                   category === 'THEATER' ? ' — The Musical' : ` ${year}`;

    events.push({
      name: `${artist}${suffix} @ ${venueCity} ${year}`,
      primaryArtist: artist,
      venueName,
      venueCity,
      category,
      genre,
      eventDate: eventDate.toISOString(),
      priceMin,
      priceMax,
    });
  }
  return events;
}
