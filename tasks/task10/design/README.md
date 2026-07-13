# T10 — Client resilience + E2E: design

Status: **DESIGNED** (approved 2026-07-12)

## Deliverables in this folder

- `mockup.html` — interactive mock of every resilience overlay (serve over HTTP; `file://` is blocked in the preview pane). Scenarios: healthy, reconnecting, offline, toasts, room-closed, ROOM_NOT_FOUND, multi-tab takeover.

## Design decisions

- **Overlay layer, not screens**: banners/toasts/modals render on top of whatever screen is active (T7 functional or T8 skinned room). Nothing about the room view changes.
- **Reconnect**: socket drop → amber "Reconnecting…" banner, controls locked, last snapshot stays visible (dimmed). Auto-retry with backoff; on reconnect, re-subscribe → server resends full snapshot → banner clears, controls re-enable from fresh `actionSeq`. Retries exhausted → red "Offline" banner with manual **Retry**.
- **`room.closed`** → blocking modal with 3 s auto-redirect countdown → lobby (snapshot cleared).
- **`ROOM_NOT_FOUND` ack** (act/join after kill or GC) → error toast + "This table is gone" modal → lobby, list refetched.
- **Multi-tab**: newest tab wins. Older tab gets a takeover modal ("Opened in another tab"), socket closed, read-only, with **Use here instead** to re-grab the session.
- **Hard refresh mid-hand**: session cookie re-auths the handshake → rejoin-as-resync → same seat, current snapshot.
- **Non-fatal action rejects** (not-your-turn, stale `actionSeq`) → warn toast; controls reconcile to the latest snapshot — no optimistic state kept.

Full event → source → UX → recovery table lives in `mockup.html` ("Behaviour matrix").

## Two-browser E2E script (P7 exit gate, from the approved plan)

Playwright, two persistent contexts (alice, bob) + a third for the spectator; dev-login via `/auth/dev?u=name`.

1. **Setup**: alice and bob dev-login in separate contexts.
2. **Lobby**: alice creates a 6-seat 5/10 room (buy-in 400–2000, 30 s timer) → room appears in bob's lobby ≤1 s → both sit with 1,000.
3. **Heads-up hand**: button acts first preflop, last postflop; raise slider min/max match `[minRaiseTo, maxRaiseTo]`; play to showdown; winning ranks named; chip total conserved.
4. **Timeout path**: idle past the action timer → auto-check/fold fires → player marked sitting out.
5. **Refresh path**: mid-hand hard refresh → same seat, own hole cards restored, hand continues.
6. **Tab-close path**: close a tab mid-hand → action times out to fold; reopen → seat retained (rejoin-as-resync).
7. **Multi-tab path**: open the room in a second tab as the same user → old tab shows the takeover modal, read-only; **Use here instead** re-grabs it.
8. **Spectator**: third profile joins as spectator — WS frames contain no hole-card strings — then sits; 3-handed blind order correct.
9. **All-in runout**: force unequal stacks all-in → auto-runout with pacing, side pots + uncalled-bet refund correct.
10. **Bust + rebuy**: busted player gets rebuy modal → applied next hand.
11. **Kill-server recovery**: restart server mid-session → clients show reconnect → acts get `ROOM_NOT_FOUND` → toast → lobby.
12. **Teardown**: all leave → room GC'd from the lobby; private room joinable only by code.
