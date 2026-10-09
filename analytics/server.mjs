#!/usr/bin/env node
// Local hand-archive analytics server. Reads the compressed archive files the
// game server appends under data/hand-archive/ and serves a dashboard over
// them. Depends only on Node built-ins so it runs even when the database and
// the game server are down.
import { createServer } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const archiveDir = process.env.HAND_ARCHIVE_DIR
  ?? path.join(here, '..', 'data', 'hand-archive');
const port = Number(process.env.ANALYTICS_PORT ?? 8090);
const host = process.env.ANALYTICS_HOST ?? '127.0.0.1';

function normalizedHostname(value) {
  const text = value.trim().toLowerCase();
  const candidate = text.includes(':') && !text.startsWith('[') ? `[${text}]` : text;
  const parsed = new URL(`http://${candidate}`);
  if (parsed.username || parsed.password || parsed.port || parsed.pathname !== '/'
      || parsed.search || parsed.hash || parsed.host.toLowerCase() !== candidate.toLowerCase()) {
    throw new Error(`Invalid analytics hostname: ${value}`);
  }
  return parsed.hostname.replace(/^\[|\]$/g, '');
}

function isLoopbackHostname(value) {
  const hostname = normalizedHostname(value);
  if (hostname === 'localhost' || hostname === '::1') return true;
  const octets = hostname.split('.').map(Number);
  return octets.length === 4 && octets.every(Number.isInteger) && octets[0] === 127;
}

const configuredAllowedHosts = (process.env.ANALYTICS_ALLOWED_HOSTS ?? '')
  .split(',').map(value => value.trim()).filter(Boolean);
const loopbackOnly = isLoopbackHostname(host);
if (!loopbackOnly && configuredAllowedHosts.length === 0) {
  throw new Error('ANALYTICS_ALLOWED_HOSTS is required when ANALYTICS_HOST is not loopback');
}
const allowedHostnames = new Set((configuredAllowedHosts.length > 0
  ? configuredAllowedHosts
  : [host, 'localhost', '127.0.0.1', '::1']).map(normalizedHostname));
const authUsername = process.env.ANALYTICS_USERNAME ?? '';
const authPassword = process.env.ANALYTICS_PASSWORD ?? '';
if (!loopbackOnly && (!authUsername || !authPassword)) {
  throw new Error('ANALYTICS_USERNAME and ANALYTICS_PASSWORD are required for shared analytics access');
}

// ---------------------------------------------------------------------------
// Archive loading. Each file is a sequence of independent gzip members, one
// JSON line per hand; Node's gunzip decodes concatenated members natively.
// Files are cached by (mtime, size) so repeated requests only re-read files
// the game server has appended to.
// ---------------------------------------------------------------------------
const fileCache = new Map(); // path -> { mtimeMs, size, records }

function parseFile(filePath) {
  const raw = gunzipSync(readFileSync(filePath)).toString('utf8');
  const records = [];
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    try {
      records.push(JSON.parse(line));
    } catch {
      // A torn trailing line after a crash is expected; skip it.
    }
  }
  return records;
}

function loadHands() {
  if (!existsSync(archiveDir)) return [];
  const byKey = new Map();
  const files = readdirSync(archiveDir)
    .filter((name) => /^hands-\d{4}-\d{2}-\d{2}\.jsonl\.gz$/.test(name))
    .sort();
  for (const name of files) {
    const filePath = path.join(archiveDir, name);
    let stat;
    try {
      stat = statSync(filePath);
    } catch {
      continue;
    }
    const cached = fileCache.get(filePath);
    let records;
    if (cached && cached.mtimeMs === stat.mtimeMs && cached.size === stat.size) {
      records = cached.records;
    } else {
      try {
        records = parseFile(filePath);
      } catch (err) {
        console.error(`skipping unreadable archive file ${name}: ${err.message}`);
        continue;
      }
      fileCache.set(filePath, { mtimeMs: stat.mtimeMs, size: stat.size, records });
    }
    for (const record of records) {
      if (!record || !record.roomId || !record.handId) continue;
      byKey.set(`${record.roomId}:${record.handId}`, record);
    }
  }
  return [...byKey.values()].sort((a, b) =>
    (a.completedAt ?? '').localeCompare(b.completedAt ?? ''));
}

// ---------------------------------------------------------------------------
// Filtering + aggregation
// ---------------------------------------------------------------------------
function applyFilters(hands, query) {
  const roomId = query.get('roomId');
  const playerId = query.get('playerId');
  const from = query.get('from');
  const to = query.get('to');
  return hands.filter((hand) => {
    if (roomId && hand.roomId !== roomId) return false;
    if (from && hand.completedAt < from) return false;
    if (to && hand.completedAt > to) return false;
    if (playerId && !(hand.players ?? []).some((p) => p.userId === playerId)) return false;
    return true;
  });
}

function summary(hand) {
  const { events, ...rest } = hand;
  return rest;
}

function computeStats(hands) {
  const players = new Map();
  const perDay = new Map();
  let totalPot = 0;
  let biggestPot = null;
  const rooms = new Set();

  for (const hand of hands) {
    rooms.add(hand.roomId);
    totalPot += hand.potAwarded ?? 0;
    if (!biggestPot || (hand.potAwarded ?? 0) > biggestPot.potAwarded) {
      biggestPot = {
        potAwarded: hand.potAwarded ?? 0,
        roomId: hand.roomId,
        roomName: hand.roomName,
        handId: hand.handId,
        completedAt: hand.completedAt,
      };
    }
    const day = (hand.completedAt ?? '').slice(0, 10);
    const dayEntry = perDay.get(day) ?? { date: day, hands: 0, pot: 0 };
    dayEntry.hands += 1;
    dayEntry.pot += hand.potAwarded ?? 0;
    perDay.set(day, dayEntry);

    const preflopActed = new Map(); // userId -> {vpip, pfr}
    for (const event of hand.events ?? []) {
      if (event.type !== 'PLAYER_ACTED' || event.phase !== 'PREFLOP' || !event.userId) continue;
      const entry = preflopActed.get(event.userId) ?? { vpip: false, pfr: false };
      if (event.action === 'CALL' || event.action === 'RAISE_TO') entry.vpip = true;
      if (event.action === 'RAISE_TO') entry.pfr = true;
      preflopActed.set(event.userId, entry);
    }

    const showdown = hand.completionReason === 'SHOWDOWN';
    for (const player of hand.players ?? []) {
      const entry = players.get(player.userId) ?? {
        userId: player.userId,
        name: player.displayName ?? player.userId.slice(0, 8),
        hands: 0, vpip: 0, pfr: 0, showdowns: 0, showdownWins: 0,
        net: 0, won: 0, biggestPotWon: 0,
      };
      if (player.displayName) entry.name = player.displayName;
      entry.hands += 1;
      const pre = preflopActed.get(player.userId);
      if (pre?.vpip) entry.vpip += 1;
      if (pre?.pfr) entry.pfr += 1;
      if (showdown && !player.folded) {
        entry.showdowns += 1;
        if ((player.won ?? 0) > 0) entry.showdownWins += 1;
      }
      entry.net += player.net ?? 0;
      entry.won += player.won ?? 0;
      if ((player.won ?? 0) > entry.biggestPotWon) entry.biggestPotWon = player.won ?? 0;
      players.set(player.userId, entry);
    }
  }

  const playerRows = [...players.values()]
    .map((p) => ({
      userId: p.userId,
      name: p.name,
      hands: p.hands,
      vpipPct: p.hands ? Math.round((100 * p.vpip) / p.hands) : 0,
      pfrPct: p.hands ? Math.round((100 * p.pfr) / p.hands) : 0,
      showdowns: p.showdowns,
      showdownWins: p.showdownWins,
      net: p.net,
      won: p.won,
      biggestPotWon: p.biggestPotWon,
    }))
    .sort((a, b) => b.net - a.net);

  return {
    overview: {
      hands: hands.length,
      players: players.size,
      rooms: rooms.size,
      totalPot,
      biggestPot,
      firstAt: hands[0]?.completedAt ?? null,
      lastAt: hands[hands.length - 1]?.completedAt ?? null,
    },
    players: playerRows,
    handsPerDay: [...perDay.values()].sort((a, b) => a.date.localeCompare(b.date)),
  };
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------
function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
  });
  res.end(payload);
}

const publicFiles = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/index.html', ['index.html', 'text/html; charset=utf-8']],
  ['/app.js', ['app.js', 'text/javascript; charset=utf-8']],
  ['/styles.css', ['styles.css', 'text/css; charset=utf-8']],
]);

const contentSecurityPolicy = [
  "default-src 'none'",
  "script-src 'self'",
  "script-src-attr 'none'",
  "style-src 'self'",
  "connect-src 'self'",
  "img-src 'self'",
  "base-uri 'none'",
  "form-action 'none'",
  "frame-ancestors 'none'",
  "object-src 'none'",
].join('; ');

function requestAuthority(req) {
  const raw = req.headers.host;
  if (typeof raw !== 'string' || !raw || raw.includes(',') || /[\s\\/]/.test(raw)) return null;
  try {
    const parsed = new URL(`http://${raw}`);
    if (parsed.username || parsed.password || parsed.pathname !== '/' || parsed.search || parsed.hash
        || !parsed.port || parsed.host.toLowerCase() !== raw.toLowerCase()) return null;
    const address = server.address();
    if (!address || typeof address === 'string' || Number(parsed.port) !== address.port) return null;
    const hostname = parsed.hostname.replace(/^\[|\]$/g, '').toLowerCase();
    return allowedHostnames.has(hostname) ? { raw, hostname } : null;
  } catch {
    return null;
  }
}

function safeEqual(actual, expected) {
  const left = Buffer.from(actual);
  const right = Buffer.from(expected);
  return left.length === right.length && timingSafeEqual(left, right);
}

function authenticated(req) {
  if (loopbackOnly) return true;
  const authorization = req.headers.authorization;
  if (typeof authorization !== 'string' || !authorization.startsWith('Basic ')) return false;
  try {
    const decoded = Buffer.from(authorization.slice(6), 'base64').toString('utf8');
    const separator = decoded.indexOf(':');
    return separator >= 0
      && safeEqual(decoded.slice(0, separator), authUsername)
      && safeEqual(decoded.slice(separator + 1), authPassword);
  } catch {
    return false;
  }
}

function apiRequestAllowed(req, authority) {
  const origin = req.headers.origin;
  if (origin) {
    try {
      const parsed = new URL(origin);
      if (parsed.protocol !== 'http:' || parsed.host.toLowerCase() !== authority.raw.toLowerCase()) return false;
    } catch {
      return false;
    }
  }
  const fetchSite = req.headers['sec-fetch-site'];
  return fetchSite === undefined || fetchSite === 'same-origin' || fetchSite === 'none';
}

const server = createServer((req, res) => {
  res.setHeader('Content-Security-Policy', contentSecurityPolicy);
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  try {
    const authority = requestAuthority(req);
    if (!authority) {
      sendJson(res, req.headers.host ? 421 : 400, { error: 'invalid host' });
      return;
    }
    if (!authenticated(req)) {
      res.setHeader('WWW-Authenticate', 'Basic realm="Poker analytics", charset="UTF-8"');
      sendJson(res, 401, { error: 'authentication required' });
      return;
    }
    const url = new URL(req.url, 'http://localhost');
    const publicFile = publicFiles.get(url.pathname);
    if (publicFile) {
      const [filename, contentType] = publicFile;
      const body = readFileSync(path.join(here, 'public', filename));
      res.writeHead(200, { 'content-type': contentType, 'cache-control': 'no-store' });
      res.end(body);
      return;
    }
    if (url.pathname.startsWith('/api/') && !apiRequestAllowed(req, authority)) {
      sendJson(res, 403, { error: 'cross-origin request rejected' });
      return;
    }
    if (url.pathname === '/api/hands') {
      const hands = applyFilters(loadHands(), url.searchParams);
      sendJson(res, 200, hands.map(summary).reverse());
      return;
    }
    if (url.pathname === '/api/hand') {
      const roomId = url.searchParams.get('roomId');
      const handId = Number(url.searchParams.get('handId'));
      const hand = loadHands().find((h) => h.roomId === roomId && h.handId === handId);
      if (!hand) {
        sendJson(res, 404, { error: 'hand not found' });
        return;
      }
      sendJson(res, 200, hand);
      return;
    }
    if (url.pathname === '/api/stats') {
      sendJson(res, 200, computeStats(applyFilters(loadHands(), url.searchParams)));
      return;
    }
    sendJson(res, 404, { error: 'not found' });
  } catch (err) {
    console.error(`request failed ${req.method} ${req.url}:`, err);
    sendJson(res, 500, { error: 'internal error' });
  }
});

server.listen(port, host, () => {
  console.log(`poker analytics on http://${host}:${server.address().port} (archive: ${archiveDir})`);
});
