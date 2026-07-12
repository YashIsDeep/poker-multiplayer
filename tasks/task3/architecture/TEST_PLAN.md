# T3 Verification Plan

## Test strategy

Tests are deterministic and use `Deck.of` with explicit draw order unless the
test is the fixed-seed simulation. Unit tests exercise package-private seat,
betting, and settlement helpers; scenario tests drive only the public
`HoldemHand` API. Every public-API step asserts `snapshot`, optional
`legalActions`, ordered transition batches, stable event payloads, and chip
conservation. Transition tests prove that each authoritative `snapshotAfter`
frame can be presented without reconstructing engine state.

`EngineTestFixtures` provides named cards, rigged-deck construction, compact
player/seat builders, action helpers, and assertions that total chips and card
uniqueness remain intact. It must not duplicate production betting or pot
algorithms.

## Start and order vectors

`HoldemHandStartTest` and `HoldemHandOrderTest` cover:

- null config/player list/deck and a null player fail before the deck is drawn;
- a partially consumed deck is rejected before another card is drawn;
- fewer than two, more than nine, duplicate IDs, duplicate/out-of-range seats,
  nonpositive stacks, and a nonparticipant button are rejected;
- overflow-prone chip totals are rejected through exact arithmetic;
- every failed start leaves `deck.remaining()` unchanged, while successful
  start consumes all 52 cards and later hand progression is independent of the
  exhausted caller-held deck reference;
- 3-handed blind seats, two-pass deal order, and first preflop actor;
- sparse seats wrap correctly around seat 8 to seat 0;
- heads-up button/SB, other player/BB, deal order, preflop button action, and
  postflop BB action;
- short stacks post partial SB/BB without lowering the nominal preflop call or
  minimum raise; when both blinds post below one BB, a deeper first actor still
  faces a full-BB call and a two-BB minimum full raise;
- the BB receives an option after all other active players call;
- folded and all-in seats are skipped without changing circular order;
- snapshots and all nested collections are immutable defensive copies; and
- players, contributor/eligible IDs, pots, awards, events, and transitions use
  their documented deterministic order regardless of input list/hash order.

## Betting-rule vectors

`HoldemHandBettingTest` covers:

- check is legal only with zero to call; call only with a positive amount;
- call commits `min(toCall, stack)` and marks a zero-stack player all-in;
- `RaiseTo` means current-street total, not an increment;
- a raise target at/below `currentBet`, above the player's maximum, or below the
  minimum while chips remain is rejected atomically;
- opening postflop minimum is the nominal BB;
- each full raise updates the minimum by its actual raise size;
- an all-in under-raise increases `currentBet` but preserves the last full raise
  size;
- a player who acted cannot reraise over one insufficient all-in;
- a player who has not acted may raise over that same short all-in;
- cumulative short all-ins reopen action exactly when their total increase
  reaches the last full raise size;
- a player can always fold when asked to act, even with zero to call; and
- null command inputs use `NullPointerException`; every expected rejection
  exposes the documented stable `ActionRejectionReason` and validation
  precedence; and
- rejected/out-of-turn actions leave snapshots value-equal via `equals`.

Near-`Long.MAX_VALUE` vectors cover checked add/subtract/multiply behavior,
including a legal-action state where the mathematical full minimum is
unrepresentable. In that case no non-all-in full raise is exposed, while a
strictly larger short all-in remains representable when available.

## Street and completion vectors

`HoldemHandProgressionTest` covers:

- a round does not close while the BB option or any unmatched caller remains;
- flop/turn/river deal 1 burn + 3/1/1 board cards in exact deck order;
- postflop commitments reset while hand commitments persist;
- action begins left of the button on each multiway postflop street;
- one remaining live player completes immediately with `FOLD_WIN`;
- all-in players are skipped, but a live player facing a bet must still respond;
- when no more betting is possible, all remaining streets run out in one action
  result and transitions remain in board/settlement order;
- a flop-to-showdown automatic runout returns one authoritative frame per dealt
  street plus settlement; replaying only those frames yields the exact phase,
  board, commitments, pots, awards, and stacks without production-helper calls;
- `ActionResult.snapshot()` equals the final transition snapshot, and transition
  sequence numbers are strictly monotonic across start and later actions;
- `legalActions()` is present exactly while the aggregate waits for input and
  empty for immediately completed and normally completed hands;
- river completion settles once and further actions are rejected; and
- trusted state contains hole cards while documenting that it is not a wire DTO;
  public snapshots/events expose no burn-card field or burn-card value.

## Pot/refund unit vectors

`PotSettlementTest` uses direct ledgers to cover:

- equal commitments produce no refund;
- one highest commitment refunds only its excess over second place;
- a folded player's commitment counts when finding the matched amount;
- refund updates stack, street commitment, and hand commitment consistently;
- immediately after a unique leading bet/raise, matched pots plus
  `PendingUnmatched` equal all commitments and no provisional one-contributor
  pot is exposed;
- after a call ties the largest commitment, pending unmatched is empty and all
  commitments are represented by matched pots;
- after a fold/round close, the actual refund is applied before settlement and
  the transition snapshot has neither pending unmatched nor a one-contributor
  pot;
- commitments `100/100/100` create one 300 main pot;
- commitments `50/100/200` refund 100, then create a 150 main and 100 side pot;
- commitments `25/75/200/200` create layers 100, 150, and 250;
- folded contributors enlarge layers but never appear in eligibility;
- each layer amount is positive and all layers sum to commitments;
- near-limit layer widths and contributor multiplication use checked arithmetic;
- tied main-pot winners can differ from a sole side-pot winner;
- exact splits emit no odd chip;
- one and multiple odd chips go to tied winners left of the button, skipping
  tied losers, empty seats, folded seats, and nonwinning eligible players; and
- each pot independently restarts its odd-chip scan left of the button.

## Event and hidden-information contract vectors

Dedicated event tests assert every sealed subtype's exact fields and causal
order. They cover the initial blind/deal batch, a plain action, a refund, every
street, split and sole-winner awards, and both completion reasons. They also
assert:

- `winnerAwards` is an ordered value list rather than a map;
- the initial transition is emitted only after both hole cards exist for every
  player, so its snapshot satisfies normal snapshot invariants; an immediately
  input-free start includes its boundary refund before that frame;
- every accepted command has a nonempty action transition; a closing action
  groups `PlayerActed` then any refund before its ledger-stable frame;
- each automatic street has its own transition and settlement orders all
  pot transitions main-to-side before a final `HandCompleted` transition;
- settlement resets street commitments once, then each pot transition removes
  exactly that layer from hand commitments, credits its winners, preserves
  chips, and exposes authoritative accumulated awards/remaining pots without T6
  applying deltas;
- `HoleCardDealt` is classified hidden/private-to-player and all trusted
  containers are explicitly marked unsafe for generic serialization;
- neither snapshot reflection nor any event payload exposes internal burn
  cards; and
- a future T6 allow-list/redaction regression test is required before trusted
  transitions may cross the websocket boundary.

## Required end-to-end scenarios

`HoldemHandScenarioTest` contains at least these eleven named public-API scenarios:

1. **Three-handed BB option** — SB calls, button calls, BB checks; flop begins at
   the SB-side seat left of the button.
2. **Heads-up order** — button/SB acts first preflop and BB acts first postflop.
3. **Full raise chain** — raise sizes update correctly and all players receive
   the required response after the last full raise.
4. **Short all-in no reopen** — a prior caller may call/fold but cannot reraise.
5. **Cumulative short all-ins reopen** — combined increases equal one full
   raise, restoring the prior caller's raise option.
6. **Partial blind all-in runout** — a short blind is all-in, remaining action
   closes, and the board runs out without requesting action from that seat.
7. **Immediate fold win** — unmatched excess is refunded and the last live
   player receives all matched chips without hand evaluation.
8. **Three-way side pots** — different stack caps produce correct main and side
   pots won by different made hands.
9. **Folded dead money** — a folded large contributor increases pots but is
   ineligible at showdown.
10. **Tied showdown odd chip** — a tied pot not divisible by winner count awards
    its remainder to the first tied winner left of the button.

11. **Nominal bring-in over two short blinds** — both SB and BB post all-in for
    less than one configured BB; a deeper first actor faces a full-BB call and a
    two-BB minimum full raise before the hand runs out.

Recommended additional scenarios lock down board-plays ties, multiple side pots
with different tie groups, sparse-seat odd-chip wraparound, and river all-in
with an uncalled-bet refund.

## 10,000-hand chip-conservation simulation

`HoldemHandSimulationTest` uses one documented constant seed. For each hand it:

1. cycles player counts across 2–9, then chooses distinct seats, positive
   stacks, a participating button, and legal blind values from bounded
   deterministic generators;
2. supplies a deterministic shuffled T2 `Deck`;
3. repeatedly reads optional `legalActions`; when present it selects one legal
   action with weighted choices covering folds, checks, calls, minimum/full
   raises, and all-ins, and when empty it requires the hand to be complete;
4. after every accepted action asserts chip conservation, nonnegative ledgers,
   unique cards, valid actor, and matched-pot-plus-pending reconciliation;
5. enforces a generous action-count ceiling as a state-machine loop detector;
6. requires `COMPLETE`, empty legal actions, zero commitments, awards equal to
   contributions, and final stacks summing exactly to starting chips; and
7. records branch counters for diagnostics without making pseudo-random hits a
   pass/fail condition.

The simulation tests termination and invariants, not semantic branch quotas or
winner frequencies. Short raises, side pots, ties, and odd chips remain required
deterministic scenario/unit tests. A failing iteration prints the seed, hand
index, starting seats/stacks/button, rigged deck order, and accepted action trace
so the hand can be promoted into a small regression test.

## Build and purity checks

Run from the repository root:

```powershell
npm.cmd test
```

Equivalent server-only command:

```powershell
Set-Location server
.\mvnw.cmd -B -ntp test
```

`EngineArchitectureTest` is part of the normal Maven suite and uses an
architecture-test dependency to reject production dependencies from
`com.poker.engine.game` to Spring, Jackson, JPA, websocket, filesystem/I/O,
network, clock, scheduler, logging, or randomness packages. A focused source
assertion additionally rejects fully qualified forbidden APIs plus
`System.currentTimeMillis`, `System.nanoTime`, and `Math.random`. The final
manual import scan is retained only as a readable diagnostic; it is not the
architecture gate.
