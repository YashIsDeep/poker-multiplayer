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

Documented when auth lands (P3): you'll register a Google OAuth client + Discord application with redirect URIs
`http://localhost:5173/login/oauth2/code/google` and `http://localhost:5173/login/oauth2/code/discord`.
Local development uses a dev-only login (`/auth/dev?u=name`, `dev` profile) so multiplayer can be tested without real accounts.
