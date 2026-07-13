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

The production build packages the React app inside the Spring Boot jar and
serves the complete application from port `8080` by default. Set
`SESSION_COOKIE_SECURE=true` when serving it over HTTPS. A production reverse
proxy must forward the original host and protocol so Spring generates the
public HTTPS OAuth callback URL.

## Layout

```
server/   Spring Boot — com.poker.{engine,game,ws,auth,db,api}
client/   Vite + React app
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
