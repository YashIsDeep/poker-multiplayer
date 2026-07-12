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

Client: http://localhost:5173 (proxies `/api`, `/auth`, `/oauth2`, `/login`, `/logout`, `/ws` to the server on :8080).

## Layout

```
server/   Spring Boot — com.poker.{engine,game,ws,auth,db,api}
client/   Vite + React app
```

`server/src/main/java/com/poker/engine` is pure, deterministic game logic (no Spring, no I/O) — run `npm test` for the engine suites.

## OAuth setup

Register a Google OAuth client and Discord application with these redirect URIs:

`http://localhost:5173/login/oauth2/code/google`

`http://localhost:5173/login/oauth2/code/discord`

The `oauth` profile reads `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`,
`DISCORD_CLIENT_ID`, and `DISCORD_CLIENT_SECRET` from the environment. Start it
with `spring-boot.run.profiles=oauth` (or `dev,oauth`) and never commit the
secrets. Local development uses the loopback-only dev login
(`/auth/dev?u=name`, `dev` profile) so multiplayer can be tested without real
accounts; the dev route is intentionally absent outside that profile.
