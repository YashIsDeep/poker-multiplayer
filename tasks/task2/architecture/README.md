# T2 Code Architecture — Deck and Hand Evaluator

## Goal

Add a pure-Java card module under `com.poker.engine.cards` that gives the later game engine:

- a stable 52-card encoding;
- deterministic, injected-RNG deck shuffling and drawing;
- correct five-card ranking and seven-card best-hand evaluation;
- total ordering for showdown comparisons; and
- human-readable hand descriptions.

The module has no Spring, persistence, JSON, logging, clock, or network dependencies. Its behavior is deterministic for a given input and RNG.

## Package and file layout

```text
server/src/main/java/com/poker/engine/cards/
  Card.java
  Rank.java
  Suit.java
  Deck.java
  HandCategory.java
  HandRank.java
  HandEvaluator.java

server/src/test/java/com/poker/engine/cards/
  CardTest.java
  DeckTest.java
  HandEvaluatorCategoryTest.java
  HandEvaluatorTieBreakTest.java
  HandEvaluatorSevenCardTest.java
  HandEvaluatorExhaustiveTest.java
```

Keeping the entire T2 surface in one package avoids coupling foundational cards to T3 game-state classes. Tests mirror the production package and split by behavior so a failing rule is easy to locate.

## Public model

### `Rank`

An enum ordered from weakest to strongest:

```java
TWO(2, '2'), THREE(3, '3'), ... TEN(10, 'T'),
JACK(11, 'J'), QUEEN(12, 'Q'), KING(13, 'K'), ACE(14, 'A')
```

It exposes `value()`, `symbol()`, and validated `fromSymbol(char)` and `fromValue(int)` lookups. Poker comparisons use `value()` rather than relying on enum ordinals.

### `Suit`

An enum with a deliberately fixed encoding order:

```java
CLUBS(0, 'c'), DIAMONDS(1, 'd'), HEARTS(2, 'h'), SPADES(3, 's')
```

It exposes `code()`, `symbol()`, and validated `fromSymbol(char)` and `fromCode(int)` lookups. Suit never contributes to hand strength.

### `Card`

`Card` is an immutable `record Card(int code)` where valid codes are `0..51`:

```text
code = (rank.value - 2) * 4 + suit.code
rank = Rank.fromValue((code >> 2) + 2)
suit = Suit.fromCode(code & 3)
```

This is the master plan's rank-major encoding: rank falls out of a bit shift and suit out of a mask, so the evaluator can index rank counts without division.

Public operations:

```java
public Card                         // compact constructor validates 0..51
public static Card of(Rank rank, Suit suit)
public static Card parse(String text)
public Rank rank()
public Suit suit()
public String toString()
```

Canonical text is two ASCII characters, rank first and lowercase suit second: `2c`, `Td`, `Qh`, `As`. `parse` accepts rank and suit letters case-insensitively but rejects null, wrong-length, or unknown values. The integer code is the transport/storage identity; display text remains an adapter concern.

### `Deck`

`Deck` owns a private 52-element `Card[]` and a draw cursor. It is mutable by design but never exposes its backing array.

```java
public static Deck shuffled(RandomGenerator random)
public static Deck of(List<Card> drawOrder)
public Card draw()
public List<Card> draw(int count)
public int remaining()
```

`shuffled` starts from cards in ascending code order and applies Fisher-Yates from index 51 down to 1:

```java
for (int i = cards.length - 1; i > 0; i--) {
    int j = random.nextInt(i + 1);
    swap(cards, i, j);
}
```

This makes randomness injectable without tying production code to a seed or RNG implementation. Exactly 51 bounded RNG calls are made. `draw()` throws `NoSuchElementException` when empty. `draw(count)` rejects negative values and counts above `remaining()`, and returns an immutable snapshot; `draw(0)` is legal, returns an empty list, and leaves the cursor untouched.

`of` exists for rigged deals, which the master plan requires for T3's `startHand(riggedDeck)` scenario tests: it validates exactly 52 unique cards and draws them in the given order (first element first) from a defensive copy of the input. Production dealing always goes through `shuffled`.

There is intentionally no reshuffle or discard-pile behavior. A new hand gets a new deck. `Deck` is not thread-safe; T3 confines each deck to its room lock per the master plan's concurrency model.

## Hand strength model

### `HandCategory`

The enum is ordered from weakest to strongest and carries an explicit strength:

1. `HIGH_CARD`
2. `ONE_PAIR`
3. `TWO_PAIR`
4. `THREE_OF_A_KIND`
5. `STRAIGHT`
6. `FLUSH`
7. `FULL_HOUSE`
8. `FOUR_OF_A_KIND`
9. `STRAIGHT_FLUSH`

The explicit strength prevents comparison behavior from silently changing if source order is edited later.

### `HandRank`

`HandRank` is an immutable final value class implementing `Comparable<HandRank>`:

```java
public final class HandRank implements Comparable<HandRank> {
    private final HandCategory category;
    private final List<Rank> tieBreakRanks;

    // Package-private; validates and copies its inputs.
    HandRank(HandCategory category, List<Rank> tieBreakRanks) { /* ... */ }

    public HandCategory category() { /* ... */ }
    public List<Rank> tieBreakRanks() { /* ... */ }
    public String describe() { /* ... */ }
    public int compareTo(HandRank other) { /* ... */ }
    public boolean equals(Object other) { /* ... */ }
    public int hashCode() { /* ... */ }
}
```

The package-private constructor prevents callers outside the card module from manufacturing evaluator results. It rejects nulls, makes an immutable copy of the tie-break list, and validates the vector length expected for the category. Public accessors expose only immutable state. `equals` and `hashCode` use category plus tie-break ranks. Comparison first uses category strength, then compares `tieBreakRanks` lexicographically by rank value. Equal poker hands compare as zero; suits are never a tie-breaker.

Tie-break vectors are always strongest component first:

| Category | Tie-break vector |
| --- | --- |
| High card | five ranks descending |
| One pair | pair, then three kickers descending |
| Two pair | high pair, low pair, kicker |
| Three of a kind | trips, then two kickers descending |
| Straight | high card only; wheel uses `FIVE` |
| Flush | five ranks descending |
| Full house | trips, then pair |
| Four of a kind | quads, then kicker |
| Straight flush | high card only; wheel uses `FIVE` |

`describe()` is derived only from category and tie-break ranks. It deliberately omits kickers from prose while retaining them in comparison data. Exact forms are:

| Category | Example description |
| --- | --- |
| High card | `High Card, Ace` |
| One pair | `Pair of Queens` |
| Two pair | `Two Pair, Kings and Fours` |
| Three of a kind | `Three of a Kind, Sevens` |
| Straight | `Nine-high Straight` |
| Flush | `Ace-high Flush` |
| Full house | `Tens full of Sixes` |
| Four of a kind | `Four of a Kind, Fives` |
| Straight flush | `Nine-high Straight Flush` |

Wheel descriptions use `Five-high`. A royal flush remains `Ace-high Straight Flush` rather than a separate category. Rank-name singular/plural formatting is private to `HandRank`; it does not expand the public `Rank` API.

## Evaluator API

`HandEvaluator` is a final, stateless utility class with a private constructor:

```java
public static HandRank rank5(List<Card> cards)
public static HandRank evaluate7(List<Card> cards)
```

Both methods reject a null list with `NullPointerException`; null elements, duplicate cards, and counts other than exactly five or seven fail with `IllegalArgumentException`. Invalid input never produces a partial result.

### Five-card algorithm

`rank5` performs one pass to build:

- `int[15] rankCounts`, indexed by poker rank value;
- a four-element suit count array;
- a descending rank list; and
- a 13-bit distinct-rank mask.

It derives:

- `flush` when one suit count is five;
- straight high rank from the mask, checking ace-low `A-2-3-4-5` explicitly;
- repeated-rank groups by scanning rank 14 down to 2.

Categories are checked in descending strength order. The method constructs exactly one `HandRank` from the matching category and canonical tie-break vector. It does not use card sort order or suit order to decide ties.

### Seven-card algorithm

`evaluate7` enumerates all index combinations `a < b < c < d < e` from the seven cards. There are exactly 21 combinations. Each five-card subset goes through the same internal ranking routine used by `rank5`; the greatest `HandRank` is returned.

The public method validates the seven-card set once. A private trusted five-card path avoids repeating duplicate/count validation for each combination. This preserves one source of ranking truth while avoiding unnecessary validation work.

Best-of-21 is intentionally preferred over a specialized seven-card lookup table in T2: it is easy to audit, fast enough for showdown evaluation, and gives T3 a correct baseline that can be optimized behind the same API only if profiling later justifies it.

## Approved deviations from the master plan

Recorded during architecture review:

- **`HandRank` value class instead of a packed comparable int.** The plan sketched `rank5` returning `category << 20 | tiebreak nibbles`. Returning `HandRank` gives T3 type-safe ordering, `category()`, and `describe()` without bit-unpacking, and its package-private constructor keeps ranking authority inside this module. If allocation cost in the exhaustive oracle ever becomes material, the packed int can return as an internal detail of the private ranking path without changing the public API.
- **`com.poker.engine.cards` subpackage instead of the plan's flat `engine/` file listing.** Card primitives stay decoupled from T3 game-state classes; the plan's purity constraint is unchanged.

## Error and immutability contract

- Any null top-level argument fails `Objects.requireNonNull` with `NullPointerException` immediately.
- Non-null but invalid input — out-of-range codes, unparseable text, wrong list sizes, duplicate cards, or null elements inside a list — uses `IllegalArgumentException`.
- Drawing from an exhausted deck uses `NoSuchElementException`.
- No returned collection is mutable.
- No API accepts or returns Spring/domain transport objects.
- Evaluation methods hold no shared mutable state and are thread-safe.

## Integration boundary for T3

T3 should depend only on:

- `Deck.shuffled(random)`, `draw()`, and `draw(count)` while dealing, plus `Deck.of(drawOrder)` in scenario tests that rig deals;
- `Card` values in player/board state;
- `HandEvaluator.evaluate7(holeCards + board)` at showdown; and
- `HandRank.compareTo`, `category`, and `describe` for winner resolution and messages.

T3 owns burn-card policy, dealing order, board progression, winner grouping, and pot allocation. T2 does not know about players, seats, boards, pots, or game phases.

## Implementation sequence

1. Add `Rank`, `Suit`, and `Card`, then lock their encoding with round-trip tests.
2. Add `Deck` (`shuffled` and rigged `of`) and verify Fisher-Yates call bounds, uniqueness, exhaustion, deterministic injection, and exact rigged draw order.
3. Add `HandCategory` and immutable, comparable `HandRank`.
4. Implement and vector-test `rank5` category detection.
5. Add tie-break and wheel tests before implementing `evaluate7`.
6. Implement best-of-21 `evaluate7`, then board-plays and duplicate-input tests.
7. Run the exhaustive 5-card distribution test and the full Maven suite.

## Acceptance criteria

- All 52 card codes round-trip uniquely through code, rank/suit, and canonical text.
- Shuffles contain every card exactly once and are reproducible with an injected deterministic RNG.
- A rigged deck from `Deck.of` draws in exactly the given order and rejects null, duplicate, and non-52-card input.
- Fisher-Yates uses the correct shrinking inclusive range and never calls an unbounded global RNG.
- Every five-card category and every tie-break vector compare correctly.
- Wheel straights rank as five-high in both straight and straight-flush cases.
- Seven-card evaluation selects the best of exactly 21 subsets, including when the five board cards play.
- Duplicate or wrong-count inputs fail fast.
- Exhaustive five-card category counts match the known 2,598,960-hand distribution.
- `npm.cmd test` from the repository root (which runs `.\mvnw.cmd -B -ntp test` in `server/`) passes.
- Production classes under `com.poker.engine.cards` contain no Spring or I/O imports.
