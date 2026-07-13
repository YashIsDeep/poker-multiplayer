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

Copy `.env.example` to `.env` and populate the four OAuth credentials before
starting. The root commands load `.env` automatically.

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
