# T3 Code Architecture — Texas Hold'em Game Engine

## Goal

Add a pure-Java, deterministic no-limit Texas Hold'em hand engine under
`com.poker.engine.game`. The engine is the authority for:

- blind posting and heads-up/multiway action order;
- legal fold, check, call, and `raiseTo` actions;
- minimum full raises and short all-ins that do not incorrectly reopen action;
- street progression, fold wins, and automatic all-in runouts;
- unmatched-bet refunds, layered side pots, showdown ties, and odd chips; and
- auditable state, legal-action, and event outputs for the later room layer.

The module has no Spring, persistence, JSON, logging, clock, scheduler, or network
dependencies. A hand is deterministic for its inputs and supplied `Deck`. It is
mutable and intentionally not thread-safe; T6's `GameRoom` will confine every
call to the room lock.

## Scope boundary

T3 models one hand, not a long-lived table. The room layer remains responsible
for choosing eligible participants, advancing the dealer button between hands,
applying rebuys/sit-outs, creating a shuffled deck, scheduling action timers,
and deciding when the next hand starts.

T3 consumes T2 only through `Card`, `Deck`, `HandEvaluator`, and `HandRank`.
It owns burn cards, hole-card dealing, board progression, winner grouping, and
chip settlement. T3 returns complete trusted state; T6's `Redactor` is the
security boundary and must convert it into per-viewer snapshots.

## Package and file layout

```text
server/src/main/java/com/poker/engine/game/
  HoldemHand.java                 public aggregate and state machine
  HandConfig.java                blinds and seat-count constraints
  PlayerId.java                  stable engine-level player identity
  PlayerAtSeat.java              validated start-hand input
  PlayerAction.java              sealed Fold/Check/Call/RaiseTo commands
  HandPhase.java                 PREFLOP/FLOP/TURN/RIVER/COMPLETE
  CompletionReason.java          FOLD_WIN/SHOWDOWN
  HandSnapshot.java              immutable trusted state snapshot
  PlayerSnapshot.java            immutable per-seat state
  LegalActions.java              exact options for the acting seat
  BlindType.java                 SMALL_BLIND/BIG_BLIND event classification
  HandEvent.java                 sealed deterministic domain events
  HandTransition.java            ordered event batch plus authoritative frame
  ActionResult.java              final snapshot plus command transitions
  ActionRejectedException.java   typed expected-command rejection
  ActionRejectionReason.java      stable rejection reason codes
  Pot.java                       immutable contribution layer
  PendingUnmatched.java          active-hand unmatched contribution view
  PotAward.java                  immutable settlement result
  WinnerAward.java               ordered per-winner pot allocation
  BettingRound.java              package-private round bookkeeping
  SeatOrder.java                 package-private circular-order rules
  PotSettlement.java             package-private refund/pot/award logic

server/src/test/java/com/poker/engine/game/
  HoldemHandStartTest.java
  HoldemHandBettingTest.java
  HoldemHandOrderTest.java
  HoldemHandProgressionTest.java
  PotSettlementTest.java
  HandEventTest.java
  HoldemHandScenarioTest.java
  HoldemHandSimulationTest.java
  EngineArchitectureTest.java
  EngineTestFixtures.java
```

Small value types may be nested only when they are private implementation
details. Every type listed as public above gets its own file so the API remains
easy to discover and transport adapters do not depend on internals.

## Public API

### Start a hand

```java
public final class HoldemHand {
    public static HoldemHand start(
        HandConfig config,
        List<PlayerAtSeat> players,
        int buttonSeat,
        Deck deck
    );

    public List<HandTransition> initialTransitions();
    public HandSnapshot snapshot();
    public Optional<LegalActions> legalActions();
    public ActionResult act(PlayerId playerId, PlayerAction action);
}
```

`start` validates all input before changing or drawing from the deck. There must
be 2 through `config.maxSeats()` players, seat numbers must be distinct and in
`0..maxSeats-1`, player IDs must be distinct, each stack must be positive, and
`buttonSeat` must identify a participating player. Inputs are defensively
copied. The supplied deck must be fresh (`remaining() == 52`). Every argument,
player, chip total, and deck precondition is validated before the first draw; a
failed `start` leaves the deck cursor unchanged. After validation, a successful
`start` calls `deck.draw(52)` once and stores the returned immutable order behind
an aggregate-private cursor. The caller's deck is exhausted and retaining its
reference cannot influence the hand.

Chips use `long`. Addition, subtraction, and multiplication use checked
arithmetic so overflow fails instead of silently corrupting the ledger. The
legal-action algorithm avoids constructing an overflowing minimum-raise total,
as specified below.

After posting blinds and dealing hole cards, `start` applies the same automatic
transition loop used after actions. If all dealt-in players are already all-in,
the returned aggregate is therefore complete with a five-card board and final
awards; callers never need to submit a synthetic action to advance it.

`PlayerAtSeat` is an immutable record of `PlayerId id`, `int seat`, and
`long stack`. `PlayerId` is an opaque, nonblank string value so auth/database
types never enter the engine.

`HandConfig` is an immutable record:

```java
public record HandConfig(long smallBlind, long bigBlind, int maxSeats)
```

It requires `smallBlind > 0`, `bigBlind >= smallBlind`, and `2 <= maxSeats <= 9`.
The big blind is also the minimum opening bet on every postflop street and the
nominal preflop bring-in even when a blind player posts short.

### Commands

```java
public sealed interface PlayerAction {
    record Fold() implements PlayerAction {}
    record Check() implements PlayerAction {}
    record Call() implements PlayerAction {}
    record RaiseTo(long amount) implements PlayerAction {}
}
```

`RaiseTo.amount` is the player's total commitment on the current street after
the action, not a delta. This removes ambiguity for clients and timers. Callers
must use `Call` when the player's all-in total cannot exceed `currentBet`; a
`RaiseTo` that does not increase `currentBet` is rejected.

Null `playerId` or `action` arguments fail with `NullPointerException`, matching
the existing cards API. Expected gameplay rejection throws
`ActionRejectedException`, an `IllegalArgumentException` subtype carrying one
stable `ActionRejectionReason`:

```text
UNKNOWN_PLAYER
HAND_COMPLETE
NOT_ACTOR
ACTION_NOT_LEGAL
INVALID_RAISE_TO
```

T6 maps the enum, never exception prose, to acknowledgement error codes. Stale
`actionSeq` rejection remains a T6 concern and happens before calling T3.
Programming/invariant failures use `IllegalStateException` and are never
reported as client mistakes.

Validation precedence is deterministic: null check, `HAND_COMPLETE`,
`UNKNOWN_PLAYER`, `NOT_ACTOR`, then action legality. A `RaiseTo` when raising is
not an available action yields `ACTION_NOT_LEGAL`; when raising is available but
the requested total is outside the exposed legal range, it yields
`INVALID_RAISE_TO`.

Expected rejection is atomic: command validation and all fallible chip-bound
calculations complete before mutation, and a rejected command leaves a
value-equal snapshot. Once a validated command begins automatic transitions,
an unexpected invariant/programming failure is fatal to that hand rather than a
recoverable rejection.

### Legal actions

`legalActions()` returns a present value only while the aggregate is waiting for
a player command. It returns `Optional.empty()` when `start` completes the hand
immediately and after every normal completion. It describes only the current
actor and contains:

```java
public record LegalActions(
    PlayerId playerId,
    long toCall,
    boolean canFold,
    boolean canCheck,
    boolean canCall,
    long callAmount,
    boolean canRaise,
    long minimumRaiseTo,
    long maximumRaiseTo
) {}
```

When raising is unavailable, both raise bounds are zero. `callAmount` is
`min(toCall, stack)` and may put the player all-in. Fold is legal whenever a
player is asked to act, including when `toCall == 0`; check is legal only when
`toCall == 0`; call is legal only when `toCall > 0`.

An all-in increase smaller than the full minimum remains legal only when the
actor's `maximumRaiseTo` cannot reach that minimum; in that case both exposed
bounds equal `maximumRaiseTo`. This is the sole short-raise exception and keeps
the client from guessing whether an all-in is allowed. If
`currentBet + lastFullRaiseSize` would overflow, no full raise is representable;
raising is unavailable unless the actor can make a strictly larger short
all-in, which is exposed with both bounds equal to that all-in total. When
raising is unavailable, both bounds are zero.

### Snapshots and events

All returned collections are immutable copies and all snapshot/event value
types implement structural `equals`/`hashCode` (records where practical).
`HandSnapshot` contains:

- phase and `Optional<CompletionReason>`, present exactly when complete;
- button/small-blind/big-blind seats and `OptionalInt actingSeat`;
- current street target and last full raise size;
- player snapshots in ascending seat order with stack, street/hand
  commitments, folded/all-in state, and hole cards;
- public board cards only; burn cards remain aggregate-private;
- matched contribution-layer pots, an optional active-hand
  `PendingUnmatched`, and awards accumulated so far (final when complete); and
- total chips fixed at hand start.

The aggregate's ordinary `snapshot()` has an acting seat exactly when it is
waiting for input. A transition frame may have no actor at a closed action
boundary or while automatic dealing/settlement is being presented. A complete
snapshot has no actor.

`HandEvent` is a sealed trusted-domain hierarchy with these exact payloads:

```text
BlindPosted(playerId, seat, blindType, amount, allIn)
HoleCardDealt(playerId, seat, cardIndex, card)
PlayerActed(playerId, seat, action, amountCommitted, streetCommitted, stackAfter)
UncalledBetRefunded(playerId, seat, amount, stackAfter)
StreetDealt(phase, cardsAdded, boardAfter)
PotAwarded(potIndex, potAmount, winnerAwards)
HandCompleted(reason)
```

`winnerAwards` is an ordered list of `(playerId, seat, amount)` values, not a
map. Events contain no burn cards. `HoleCardDealt` is hidden/private-to-player;
all other listed event fields are public after T6 conversion. The hierarchy is
still trusted as a whole and must never be generically serialized.

One `HandTransition` groups a monotonically increasing per-hand `sequence`, an
ordered nonempty event list, and the authoritative `snapshotAfter` presentation
frame after the last event in the group. A transition is emitted only after
hole-card dealing is complete; the initial blind and hole-card events can
therefore share one valid PREFLOP frame. If start already has no possible input,
any boundary refund is included in that initial event group before its frame.

Every accepted command first emits an action transition. If the command closes
the round, any `UncalledBetRefunded` event is included after `PlayerActed` in the
same group, so its no-actor frame is already ledger-stable. Ordinary advancement
then emits one `StreetDealt` transition. Automatic runout emits one transition
per newly dealt street. Settlement emits one transition per pot, containing one
`PotAwarded` and the snapshot after that pot's contributions have been removed
and its winner stacks credited; awards accumulate and remaining pots shrink.
A final `HandCompleted` transition follows the last award. T6 can pace these
frames by redacting and forwarding them in sequence without reconstructing
phase, board, commitments, pots, winners, or stacks.

`ActionResult` contains the final post-command aggregate snapshot and the
nonempty transitions caused by that accepted command. Its snapshot equals the
last transition's `snapshotAfter`. `initialTransitions()` returns the fixed,
nonempty immutable transitions created by `start`, including automatic runout
and settlement when start completes immediately; it is not an ever-growing
event log.

T6 constructs viewer DTOs through an explicit allow-list mapper. It redacts all
opponents' hole cards and any future hidden event/snapshot field. Neither trusted
snapshots nor trusted events may be passed to a websocket serializer directly.

## Internal player ledger

Each internal player entry stores:

```text
id, seat
stack
streetCommitted
handCommitted
folded
allIn
holeCards[0..1]
lastActedFacingBet (or UNACTED)
```

Every chip is in exactly one of `stack`, `handCommitted`, or a completed-hand
award already moved back into `stack`. `streetCommitted` is a view into the
current street and is always less than or equal to `handCommitted`; it is not an
additional chip bucket.

At every command boundary:

```text
sum(player.stack) + sum(player.handCommitted) == totalChipsAtStart   (active)
sum(player.stack) == totalChipsAtStart                              (complete)
```

No stack or commitment may be negative. All cards across deck draws, hole
cards, burns, and board are unique by construction.

## Dealing and seat order

Circular order always means ascending seat numbers with wraparound, skipping
unoccupied seats and, for action, folded/all-in players.

For three or more players:

- small blind is first occupied seat left of the button;
- big blind is the next occupied seat;
- hole cards are dealt one at a time starting at the small blind, two laps;
- preflop action starts left of the big blind; and
- postflop action starts with the first actionable seat left of the button.

Heads-up is the deliberate exception:

- the button posts the small blind;
- the other player posts the big blind;
- the button receives the first card and acts first preflop; and
- the big blind acts first on every postflop street.

Blinds post `min(blind, stack)` and may therefore be partial all-ins. Preflop,
the street target is the nominal big blind even if the actual BB (or both
blinds) posts short. A deeper player must therefore commit the full big blind to
call; a caller with less may use `Call` to go all-in below the target. The
nominal big blind also seeds `lastFullRaiseSize`, so the first full raise is to
at least two nominal big blinds. A non-all-in big blind remains pending when
every prior player merely calls, preserving the option to check or raise; a
short all-in big blind is skipped like any other all-in player.

Board dealing burns exactly one card before flop, turn, and river. The flop
draws three board cards; turn and river draw one each. A rigged `Deck.of(...)`
therefore produces a fully deterministic hand, including burn positions.

## Betting-round algorithm

`BettingRound` owns `currentBet`, `lastFullRaiseSize`, and per-player
`lastActedFacingBet`. `currentBet` is the street target live players must match,
not necessarily the greatest amount actually committed by a blind all-in. At a
street start, actionable players are `UNACTED`; postflop `currentBet` is zero,
while preflop it is the nominal big blind. `lastFullRaiseSize` is the nominal
big blind.

The next actor is found by walking circularly from the previous action/dealer
anchor until finding a live, non-all-in player who still owes action. A player
owes action when either:

- they have not acted during this street; or
- `streetCommitted < currentBet`.

The round closes only when every live, non-all-in player has acted and matched
`currentBet`, or when fewer than two such players remain and nobody owes a call.

### Full and short raises

For an actor with `streetCommitted = s`, stack `k`, and table bet `b`:

```text
toCall          = max(0, b - s)
maximumRaiseTo  = addExact(s, k)
fullMinimum     = (b == 0) ? bigBlind : checked b + lastFullRaiseSize
raiseSize       = raiseTo - b
```

A non-all-in raise must reach `fullMinimum`. An all-in may increase the bet by
less and is then a short raise. If `fullMinimum` is not representable as a
positive `long`, no non-all-in full raise is possible. A full raise sets
`lastFullRaiseSize = raiseSize`; a short raise leaves it unchanged.

Reopening uses the amount each player faced at their last action, not a single
global boolean. A player may raise when they have not acted this street, or
when:

```text
currentBet - lastActedFacingBet >= lastFullRaiseSize
```

This prevents one short all-in from reopening action to a player who already
acted, while correctly allowing cumulative short all-ins to reopen action once
their combined increase reaches a full raise. Players who have not yet acted
retain their raise right. On every action, store the table bet faced before the
action; for the raiser, store the new `currentBet` after applying the raise.

Examples with a 100 minimum raise:

- A calls 100, B goes all-in to 150: action returns to A for 50, but A may only
  call or fold.
- A calls 100, B goes all-in to 150, C goes all-in to 200: A is now facing a
  cumulative 100 increase and may raise.
- A checks, B goes all-in for 60 into an unopened pot: an unacted C may raise;
  when action returns to A, A may not raise until the bet reaches at least 100.

## State transitions

```text
start
  -> post blinds -> deal hole cards -> PREFLOP

PREFLOP --round closes--> deal burn + flop -> FLOP
FLOP    --round closes--> deal burn + turn -> TURN
TURN    --round closes--> deal burn + river -> RIVER
RIVER   --round closes--> settle showdown -> COMPLETE(SHOWDOWN)

any active phase --one non-folded player remains-->
  refund unmatched chips -> award all pots -> COMPLETE(FOLD_WIN)

any round boundary --no further betting is possible-->
  automatically deal remaining streets -> settle showdown -> COMPLETE(SHOWDOWN)
```

After each accepted action, the aggregate applies transitions repeatedly until
it reaches a state needing player input or `COMPLETE`. Thus the aggregate's
ordinary `snapshot()` never exposes an active hand with no legal actor, although
an immutable `HandTransition.snapshotAfter` may intentionally represent a
no-input presentation frame during automatic runout. `StreetDealt` transitions
appear in board order; settlement follows the last street transition.

"No further betting" means at most one live player has chips and no player owes
a call. If one player with chips still must answer an all-in, that action is not
skipped.

## Refund, pot, and showdown algorithm

### Uncalled-bet refund

Before a betting round advances, before a fold-win award, and before showdown,
compare total hand commitments. If exactly one player has the largest total,
refund the excess above the second-largest total commitment:

```text
refund = largestCommitment - secondLargestCommitment
```

Move the refund from both that player's hand/street commitment back to their
stack and emit `UncalledBetRefunded`. Folded players' committed chips participate
in the comparison because they can match part of a bet. If the two largest
commitments are equal, there is no refund. Running this at every round boundary
makes a settlement pot layer with only one contributor impossible.

### Active snapshot pot view

While action is still open, a unique largest commitment may contain an increment
that opponents can still call. The engine does not refund it early. For snapshot
display it derives:

```text
pending = largestCommitment - secondLargestCommitment  (only for a unique max)
matchedCommitmentOfLeader = largestCommitment - pending
```

`PendingUnmatched(playerId, seat, amount)` exposes that increment separately,
and `matchedPots` are built from the adjusted commitments. Therefore an active
snapshot obeys:

```text
sum(matchedPots.amount) + pendingUnmatched.amount == sum(handCommitted)
```

When two or more players share the maximum, `pendingUnmatched` is empty. At a
closed round boundary the real refund runs first, so transition and settlement
snapshots have no pending amount and no one-contributor pot layer.

### Layered pots

Build pots from sorted distinct positive `handCommitted` levels. Starting at
zero, for each level `L`:

```text
contributors = players with handCommitted >= L
amount       = multiplyExact(L - previousLevel, contributors.size)
eligible     = contributors excluding folded players
```

Create a pot only for a positive amount. Contributors determine its size;
eligible players determine who can win it. Pot order is main pot first, then
increasing cap. Every layer records amount, cap, contributor IDs, and eligible
IDs for diagnostics. Empty eligibility is an invariant failure, not silently
discarded money.

### Awards and odd chips

At showdown, evaluate each eligible player using their two hole cards plus the
five-card board. For each pot independently, select all eligible players tied
at the greatest `HandRank`.

Split the pot with integer division. Award one remainder chip at a time to tied
winners in circular order starting with the first winning seat left of the
button. The button itself is visited only after wrapping. This rule is applied
independently to each pot and is deterministic even with empty seats.

Settlement processes pots in main-to-side order. For each pot it removes that
layer's per-player contributions from `handCommitted`, credits winner stacks,
appends its `PotAward`, verifies conservation, and captures the pot-award
transition. After the boundary refund and before the first award, settlement
resets every `streetCommitted` to zero because no further action can occur; it
does not attempt to attribute historical pot layers back to a particular
street. Thus every paced settlement frame is a valid ledger state and T6 never
applies award deltas itself.

For a fold win, no cards are evaluated: after refunding unmatched chips, the
sole non-folded player receives every pot. Awards are moved to stacks, every
commitment is cleared, and chip conservation is checked before completion.

## Invariants and validation

The implementation centralizes an always-on `verifyInvariants()` and calls it
before every transition snapshot is captured and at the final boundary of
`start` and each accepted command. With at most nine players and 26 drawn cards,
these checks are bounded and cheap. A violation throws
`IllegalStateException`; the method remains package-private so tests can invoke
it directly. It checks:

- chip conservation and nonnegative values;
- distinct player IDs/seats and valid seat bounds;
- exactly two hole cards per dealt-in player;
- board sizes allowed by phase: 0, 3, 4, or 5;
- unique hole, board, and burn cards;
- folded/all-in players are never the actor;
- postflop `currentBet` equals the greatest street commitment while action is
  active; preflop it equals at least the nominal big blind and equals the
  greatest voluntary commitment once that exceeds the blind;
- all-in is equivalent to `stack == 0` for a non-folded active player;
- active matched pots plus pending unmatched sum exactly to commitments;
- closed-boundary/settlement pot layers have at least two contributors and sum
  exactly to commitments after refunds; and
- completed hands have zero commitments and no acting seat.

The engine never accepts client-supplied pots, action order, phase, commitments,
cards, or winners. Those are derived state.

### Deterministic collection order and equality

Every public value object has structural equality. Collections use these orders:

- players and player-ID sets/lists: ascending seat;
- pots: main pot first, then increasing contribution cap;
- eligible and contributor IDs inside a pot: ascending seat;
- pot awards: pot order, with winner awards in the left-of-button remainder
  scan order; and
- transitions: increasing sequence, with events in causal order.

No externally visible order may depend on caller list order, `HashMap`, or
`HashSet` iteration. Rejection atomicity tests compare snapshots with `equals`,
not serialization bytes.

### Executable purity boundary

`EngineArchitectureTest` uses an architecture-test dependency to prohibit
production classes under `com.poker.engine.game` from depending on Spring,
Jackson, JPA, websocket, logging, filesystem/I/O, network, scheduler, clock, or
randomness packages. A focused source rule also rejects fully qualified
forbidden APIs, `System.currentTimeMillis`/`System.nanoTime`, and `Math.random`,
which an import-only scan would miss. The manual import scan remains a quick
diagnostic, not the enforcement mechanism.

## Implementation sequence

1. Add value types, validation, immutable snapshots, seat-order helpers, and
   fixture builders.
2. Implement start-hand blind/deal behavior and lock heads-up/multiway order
   with tests.
3. Implement `BettingRound`, legal-action calculation, calls/checks/folds, full
   raises, short all-ins, cumulative reopening, and the big-blind option.
4. Add phase advancement, burn/board dealing, fold-win, and automatic all-in
   runout paths.
5. Implement unmatched refunds and contribution-layer pot construction with
   ledger-focused unit tests.
6. Integrate T2 evaluation, per-pot winner selection, ties, and odd-chip order.
7. Add the scenario suite and fixed-seed 10,000-hand conservation simulation.
8. Add `EngineArchitectureTest`, run `npm.cmd test` from the repository root,
   and use the production-import scan only as a quick diagnostic.

## Integration contract for T6

T6 creates a fresh `HoldemHand` while holding its room lock. It consumes
`initialTransitions()` once for pacing, then calls only `snapshot`,
`legalActions`, and `act` until completion. It checks the optional legal action
before accepting a command. It uses its own
monotonic `actionSeq` to reject stale timer commands before calling T3; time is
not an engine input.

The room copies final stacks from `HandSnapshot` back to its seat ledger, then
discards the hand aggregate. It advances the button to the next eligible seat
before starting another hand. T6 paces ordered transition snapshots and builds
viewer DTOs with an allow-list mapper; it must not reorder transitions,
recalculate state/winners, generically serialize trusted objects, or expose burn
cards/opponents' hole cards. T6 regression tests must prove that no viewer
payload contains a burn card or unauthorized hole card.

## Acceptance criteria

- All start-hand input validation is atomic and deterministic.
- Blind, deal, preflop, and postflop order is correct for heads-up and 3–9
  handed play, including partial blinds that do not lower the nominal preflop
  bring-in and the big-blind option.
- A successful start consumes a fresh deck into private immutable draw order;
  every failed start leaves the caller's deck unchanged.
- `RaiseTo` uses street totals; non-all-in under-raises are rejected.
- A short all-in does not reopen raising to players who already acted, while
  unacted players and cumulative-full-raise cases retain correct rights.
- Rounds close only after every required response, with no skipped all-in call.
- Fold wins and all-in runouts reach `COMPLETE` without fake player actions.
- Optional actor/legal-action state is empty exactly when no command is
  accepted, including immediately completed starts.
- Transition frames are sufficient for T6 to pace an all-in runout without
  reconstructing engine state.
- Burns and board cards follow deterministic deck order.
- Burn cards never appear in a public snapshot or event payload.
- Every unmatched excess chip is refunded before pot construction.
- Active snapshots reconcile matched pots plus pending unmatched contributions.
- Main/side-pot amounts and eligibility are correct with folded contributors.
- Ties split every pot independently and odd chips go left of the button.
- Every active and completed state conserves the exact starting chip total.
- Expected action rejection has stable reason codes and leaves a value-equal
  snapshot; all chip arithmetic is overflow-safe.
- The required scenario suite and fixed-seed 10,000-hand simulation pass.
- `npm.cmd test` passes with T1/T2 tests, and executable architecture rules prove
  production game classes have no Spring, persistence, JSON, clock, scheduler,
  logging, websocket, filesystem, I/O, network, or internal-randomness
  dependencies.
