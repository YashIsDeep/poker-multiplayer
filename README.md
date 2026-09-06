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

Client: http://127.0.0.1:5173 (proxies `/api`, `/auth`, `/oauth2`, `/login`, `/logout`, `/ws` to the server on :8080).

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
only; OAuth providers still need the exact public callback URL registered, so
the local dev login is the simplest way to test a shared tunnel.

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
With `OAUTH_ENABLED=false`, the packaged server uses the local guest login
instead of OAuth. Do not expose that mode to untrusted users.

The production build packages the React app inside the Spring Boot jar and
serves the complete application from port `8080` by default. Set
`SESSION_COOKIE_SECURE=true` when serving it over HTTPS. A production reverse
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

For production, register the same two paths on the public HTTPS origin, for
example `https://poker.example.com/login/oauth2/code/google` and
`https://poker.example.com/login/oauth2/code/discord`.

The `oauth` profile reads `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`,
`DISCORD_CLIENT_ID`, and `DISCORD_CLIENT_SECRET` from `.env`. The root `dev`
and `start` commands activate the required profiles automatically. Never commit
the secrets. Local development also enables the loopback-only dev login
(`/auth/dev?u=name`, `dev` profile) so multiplayer can be tested without real
accounts; the dev route is intentionally absent outside that profile.
