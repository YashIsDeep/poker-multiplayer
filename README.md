# Poker Multiplayer

Texas Hold'em cash games with rooms — Google/Discord sign-in, Balatro-inspired retro UI.

**Stack:** Spring Boot 3.5 (Java 21, Maven wrapper) + STOMP WebSockets · Vite + React + TypeScript · H2 file database (`./data/`)

## Prerequisites

- JDK 21
- Node 20+

## Run (dev)

```
npm install
cd client && npm install && cd ..
npm run dev
```

Client: http://127.0.0.1:5173 (proxies `/api`, `/auth`, `/oauth2`, `/login/oauth2`, `/logout`, `/ws` to the server on :8080).

To share the development client remotely, run:

```
npm run dev:proxy
```

This starts the same local stack and publishes the Vite origin, including the
API and WebSocket proxy, through a temporary localtunnel URL. The URL is
printed in the terminal. Set `TUNNEL_PROVIDER=cloudflared` or
`TUNNEL_PROVIDER=ngrok` to use an installed tunnel client instead. With ngrok,
`TUNNEL_DOMAIN` passes a reserved domain (`ngrok http --domain=<domain>`) so the
public URL stays the same across restarts. Temporary tunnels are for development
only; OAuth providers need the exact public callback URL registered. Tunnels
require OAuth credentials and disable guest login.

Development uses a standalone local H2 server on `127.0.0.1:9092`. `npm run
dev` starts it automatically. If it is already running, the command asks before
restarting it and otherwise reuses the running database. You can also manage it
directly:

```
npm run start:db
npm run stop:db
npm run restart:db
```

Copy `.env.example` to `.env`. Populate all four OAuth credentials to test
Google and Discord login, or leave all four blank to run locally with only the
development login. The root commands load `.env` automatically; a partially
filled OAuth configuration is rejected with the missing variable names.
Set `OAUTH_ENABLED=false` for a temporary local guest-login mode without
Google or Discord credentials.

To delete the local H2 database and start fresh, stop the application and the
database, then run:

```
npm run stop:db
npm run reset:db
```

Flyway recreates the schema the next time the server starts.

## Run (production)

```
npm run build
npm run start
```

To expose the packaged application through the same temporary tunnel:

```
npm run build
npm run prod:proxy
```

This targets the Spring application on port `8080` and adds the public tunnel
origin to the production WebSocket allow-list. Configure the tunnel provider
with the same `TUNNEL_PROVIDER` settings used by `dev:proxy`.
With `OAUTH_ENABLED=false`, the packaged server uses loopback-only guest login.
Public listeners and tunnels are rejected in that mode.

The production build packages the React app inside the Spring Boot jar and
serves the complete application from port `8080` by default.
`SESSION_COOKIE_SECURE=true` is the production default. A production reverse
proxy must forward the original host and protocol so Spring generates the
public HTTPS OAuth callback URL.

## Hand archive & analytics

Every completed hand is archived locally by the server, independent of the
database: `com.poker.analytics` appends one JSON record per hand (players,
hole cards, board, every action, pot awards, net chips) to daily
gzip-compressed files under `data/hand-archive/hands-YYYY-MM-DD.jsonl.gz`.
Each write is an independent gzip member with an fsync, so the archive stays
readable after a crash and keeps recording while the H2 database is down.
Configure with `poker.hand-archive.enabled` / `poker.hand-archive.directory`.

To browse the archive, run the analytics dashboard (works even when the game
server and database are stopped):

```
npm run analytics
```

It serves http://127.0.0.1:8090 with per-player stats (VPIP, PFR, showdowns,
net chips), cumulative winnings and hands-per-day charts, and a full replay
of any hand. Set `ANALYTICS_PORT` / `HAND_ARCHIVE_DIR` to override defaults.
Requests must use an allowlisted hostname and the listening port. To bind beyond
loopback, also set `ANALYTICS_ALLOWED_HOSTS`, `ANALYTICS_USERNAME`, and a strong
`ANALYTICS_PASSWORD`; startup fails closed if any shared-access setting is absent.
The files decompress with standard tools too: `gzip -dc hands-*.jsonl.gz`.

## Layout

```
server/     Spring Boot — com.poker.{engine,game,ws,auth,db,api,analytics}
client/     Vite + React app
analytics/  Local hand-archive dashboard (Node, no dependencies)
```

`server/src/main/java/com/poker/engine` is pure, deterministic game logic (no Spring, no I/O) — run `npm test` for the engine suites.

## OAuth setup

Register a Google OAuth client and Discord application with these redirect URIs:

`http://127.0.0.1:5173/login/oauth2/code/google`

`http://127.0.0.1:5173/login/oauth2/code/discord`

The callback origin mirrors the host in the address bar, so register the
`localhost:5173` spelling of both paths as well if you browse that way.

For production, register the same two paths on the public HTTPS origin, for
example `https://poker.example.com/login/oauth2/code/google` and
`https://poker.example.com/login/oauth2/code/discord`.

The `oauth` profile reads `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`,
`DISCORD_CLIENT_ID`, and `DISCORD_CLIENT_SECRET` from `.env`. The root `dev`
and `start` commands activate the required profiles automatically. Never commit
the secrets. Local development enables a loopback-only guest sign-in form
(`dev` or `local-auth` profile) so multiplayer can be tested without real
accounts. The form submits a CSRF-protected POST to `/auth/dev`. Each login
creates a random identity; identical display names do not share an account.
Retain the existing browser session to reconnect as the same guest.

Read [SECURITY_DEPLOYMENT.md](SECURITY_DEPLOYMENT.md) before sharing a deployment
for the HTTPS, proxy, moderator, admin-token, and private-archive requirements.

## Ranked beta

Set `POKER_RANKED_ENABLED=true` in `.env`, then run `npm run dev`.
The feature is disabled by default. Open three separate browser profiles,
sign in, and choose **Play Ranked**. Local development identities are supported
under the `dev` profile; production ranked play requires Google or Discord.
The production `local-auth` guest mode cannot enter ranked play.

A new ranked table waits for three queued players. Later arrivals fill existing
tables, preferring the most occupied and then the oldest. Six members is the
maximum, including players temporarily standing or waiting to rebuy; their
places are reserved until they leave. Seats and opponents are assigned by the
server. Queue cancellation applies before assignment; after assignment use the
table's normal leave controls. Disconnected queue entries retain their place
for up to 30 seconds but are not assigned while offline.

Ranked rules are fixed: 5/10 blinds, 1,000-chip (100 BB) initial stacks,
30-second turns, and manual 100 BB rebuys after busting. Stacks carry forward.
Rebuys normally unlock after five completed hands. When fewer than three
active funded players remain, sequential five-second priority offers bypass
the wait. A hand starts only with 3–6 active funded players. Current hands
finish before departures take effect.

### Public table display and consolidation

Public casual tables have six seats, a fixed 100 BB initial buy-in and manual
100 BB rebuys. Stacks, bets, pots, payouts, and hand histories display big
blinds. The server keeps integer chips (5/10 blinds, 1,000-chip buy-in) so odd
chips and side pots remain exact. Public casual hands can start with two
players; ranked hands require three. Private tables retain configurable
settings and chip displays.

The ranked matcher also combines small tables. Each table must have at most
three active funded players, and their **entire membership** must fit within
six places, including players sitting out or waiting to rebuy. The older table
remains; players receive server-assigned seats. Current hands finish and
settle, then their result displays clear before anyone moves. The first table
to finish waits for the other, and queue assignments skip these reserved tables.

Stacks, rebuy counts, remaining rebuy waits, standing/sit-out state, and
connection state carry across. Moving adds no hands or earnings. A browser
moves to the destination and reconnect restores that assignment. A pair is
cancelled if it no longer fits; ordinary seating resumes. Consolidation can
repeat across more than two small tables. If fewer than three active players
remain overall, the consolidated ranked table still waits. Casual and private
tables are not automatically combined.

### Scores and public pages

- `/leaderboard`: all-time observed net BB/100, qualifying at 1,000 hands.
- `/players/{userId}`: public identity, net BB, BB/100, hand count, provisional
  status, and rank. Scores refresh every 15 seconds while visible.
- `GET /api/leaderboard?page=0&pageSize=50`: zero-based pages; page size 1–100.
- `GET /api/players/{userId}`: public profile; unknown users return 404.
- Authenticated STOMP `/app/ranked.join` and `/app/ranked.cancel` use the existing
  `{requestId, payload: {}}` envelope. Private `/user/queue/ranked` updates and
  `STATE_SYNC.state.rankedQueue` describe queue position and assignment.
- Room and lobby snapshots include `mode`. Ranked snapshots also expose
  `rankedStatus` (PLAYING, WAITING_FOR_PLAYERS, CONSOLIDATING, SETTLING).

BB/100 is 100 times average net big blinds earned per completed ranked hand.
Every dealt participant counts, including folds, timeouts, and departures.
Buy-ins/rebuys never count as earnings. Casual hands are excluded and historical
archives are not backfilled. Sorting uses exact rates before rounding; ties
use more hands, then user ID. Player profiles show no rate before the first hand.

This is observed performance, not Elo or GTO accuracy. Opponent strength,
table size, stack depth, and luck affect results, including after qualification.
Private games remain casual. Collusion/bot detection and public release
preparation are follow-up work; treat this release as a beta.

### Persistence and operations

Flyway V2 adds `ranked_hand`, `ranked_result`, and `ranked_player_total`.
All participants and totals commit in one transaction, keyed by room UUID and
hand number. A failed settlement pauses the table before publishing the
completed result; retries are idempotent. Monitor logs for
`Ranked settlement pending`, `Ranked assignment failed`, and queue publication
failures. The scheduler retries pending results and periodically repairs retry
timers.

Run one game-server instance per database. Live rooms and queues remain in
memory. A restart preserves committed statistics, closes live tables, and
voids any hand without a committed settlement; players join fresh tables.
Archive schema V2 adds `mode` and `participantCount`, preserving the existing
archive fields and private-card handling. Public statistics contain no cards
or authentication identifiers. Do not publish the private analytics archive.

Leaderboard sorting and pagination execute in the database using the ranked
read index introduced by Flyway V4. Public statistics requests have bounded
page sizes, short-lived caches, and request limits.

### Validation

Run `npm test`, `npm --prefix client run test`, and `npm run build`.
Run `npm --prefix client exec -- playwright install chromium` once, then
`npm --prefix client run e2e`. The browser suite runs Vite at
`http://127.0.0.1:15173` against the packaged server on `127.0.0.1:18080`, with
guest login, ranked play, an isolated in-memory database, and hand archiving
disabled. It does not load the root `.env`, start the shared
H2 process, or reuse an existing server by default. Rebuild after source changes.
Set `E2E_BASE_URL` to another HTTP origin on `127.0.0.1` to change the client
port, and `E2E_SERVER_PORT` to change the backend port.
To explicitly reuse an existing local stack, set `E2E_REUSE=1` and
`E2E_BASE_URL` to its origin; enable guest login and ranked play there first.
The ranked browser scenarios explicitly skip servers where ranked is disabled.
Run `node --test analytics/security.test.mjs scripts/auth-mode.test.mjs` for
the hostile-archive rendering and local/public launch-mode regressions.
