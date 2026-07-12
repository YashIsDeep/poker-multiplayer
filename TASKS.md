# Tasks

Build phases for the multiplayer Texas Hold'em app. Labels: `backlog` → `in-progress` → `done`.
IDs match the session task list (T1 = task #1), so "pick T4" is unambiguous.

- [x] `T1` `done` **P0 — Scaffold monorepo**: Spring Boot 3.5 server + Vite/React client + proxy, health/meta endpoints, dev scripts, Balatro base tokens.
- [x] `T2` `CODE_REVIEWED` **P1 — Deck + hand evaluator (pure Java)**: card encoding, injected-RNG Fisher-Yates shuffle, rank5/evaluate7 best-of-21-combos evaluator, describe(), exhaustive JUnit vector suite (wheel, kickers, board-plays).
- [x] `T3` `CODE_REVIEWED` **P2 — Game engine**: betting rounds (raiseTo totals, min-raise, no-reopen on short all-in, BB option, heads-up order), layered side pots + uncalled-bet refund + odd-chip rule, state machine with fold-win and all-in runout paths, 10 scenario tests + 10k-hand chip-conservation sim.
- [ ] `T4` `backlog` **P3 — Auth + users**: Google + Discord OAuth (Spring Security oauth2-client, custom Discord provider), user upsert into H2 via JPA, Spring Session JDBC, dev-only login (`/auth/dev?u=name`), `/api/me`.
- [ ] `T5` `backlog` **P4 — STOMP realtime + lobby + rooms**: `/ws` endpoint with session-authenticated handshake, ack pattern, RoomManager (create/join/leave, join codes, GC), seat take/stand, per-viewer room snapshots, `/topic/lobby` broadcasts.
- [ ] `T6` `backlog` **P5 — Gameplay wiring**: GameRoom ↔ engine under room lock, action timers with actionSeq guard, Redactor (hole-card security boundary + regression test), showdown pacing, rebuy/bust/sit-out queue, scripted two-client integration test.
- [ ] `T7` `backlog` **P6 — Balatro UI**: WebGL swirl background, CRT scanlines + grain overlay, elliptical table with 2-9 seats, pixel cards, action bar + raise slider, juice animations (number pop, chip fly, card bob), reconnect UX.
- [ ] `T8` `backlog` **P7 — Hardening + E2E**: double-join/multi-tab, kill-server recovery, action rate limiting, payload validation, room GC, full two-browser E2E script from the plan.
