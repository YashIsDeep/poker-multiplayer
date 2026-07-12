# T2 Verification Plan

## Card vectors

`CardTest` covers:

- all codes `0..51` are distinct and round-trip through `Card.of(rank, suit)`;
- boundary mappings for the rank-major encoding: `0 = 2c`, `3 = 2s`, `4 = 3c`, `48 = Ac`, `51 = As`;
- parse/toString round-trips for all cards;
- case-insensitive parsing (`as`, `AS`, `tH`);
- invalid codes `-1` and `52` throw `IllegalArgumentException`;
- `parse(null)` throws `NullPointerException`; one/three-character, unknown-rank, and unknown-suit text throws `IllegalArgumentException`.

## Deck vectors

`DeckTest` covers:

- a new shuffled deck has 52 remaining cards;
- drawing all cards produces each code exactly once;
- `remaining()` decreases after single and batch draws;
- batch results are immutable;
- `draw(0)` returns an empty list and leaves `remaining()` unchanged;
- empty draw throws `NoSuchElementException`; negative and oversized batch draws throw `IllegalArgumentException`; `Deck.shuffled(null)` throws `NullPointerException`;
- `Deck.of` with an explicit 52-card order draws exactly that order;
- `Deck.of` copies its input: mutating the source list afterward does not change the deck;
- `Deck.of(null)` throws `NullPointerException`; null elements, duplicate cards, and non-52-card lists throw `IllegalArgumentException`;
- two RNG instances of the same deterministic implementation and seed produce identical deck order;
- different seeds are not asserted to differ as a correctness guarantee, avoiding a probabilistic test;
- a test RNG records the 51 `nextInt(bound)` calls and verifies bounds `52, 51, ..., 2`.

## Canonical five-card category vectors

`HandEvaluatorCategoryTest` uses explicit unsorted cards:

| Category | Example | Expected vector |
| --- | --- | --- |
| High card | `As Jd 9c 6h 3s` | `A,J,9,6,3` |
| One pair | `Qs Qc Ah 8d 2s` | `Q,A,8,2` |
| Two pair | `Ks Kd 4c 4h 9s` | `K,4,9` |
| Trips | `7s 7d 7c Ah 3s` | `7,A,3` |
| Straight | `9s 8d 7c 6h 5s` | `9` |
| Flush | `Ah Jh 8h 4h 2h` | `A,J,8,4,2` |
| Full house | `Ts Td Tc 6h 6s` | `T,6` |
| Quads | `5s 5d 5c 5h As` | `5,A` |
| Straight flush | `9h 8h 7h 6h 5h` | `9` |

Additional category edges:

- `As 2d 3c 4h 5s` is a five-high straight;
- `As 2s 3s 4s 5s` is a five-high straight flush;
- `As Ks Qs Js Ts` is an ace-high straight flush, not a separate category;
- `As 2d 3c 4h 6s` is ace-high, not a straight;
- a flush containing four connected ranks is not a straight flush;
- two triples in a seven-card input can produce a full house through subset selection.

## Ordering and kicker vectors

`HandEvaluatorTieBreakTest` asserts both greater-than and equality cases:

- every category beats the category immediately below it;
- pair rank before kickers;
- each successive one-pair kicker can decide the result;
- high pair, then low pair, then kicker for two pair;
- trip rank before kickers;
- straight and straight-flush high card, with wheel below six-high;
- all five flush/high-card ranks compared lexicographically;
- full-house trips before pair;
- quad rank before kicker;
- hands with identical category/vector compare equal despite different suits, are `equals()`, and share a `hashCode`;
- `compareTo` is antisymmetric and transitive across a representative ordered set.

## Seven-card and board-play vectors

`HandEvaluatorSevenCardTest` covers:

- the best five are not the first five in input order;
- a player pair is ignored when the board makes a stronger straight;
- the five-card board plays and two players' evaluations compare equal;
- one hole card improves the board only by a kicker;
- both hole cards participate in the best hand;
- seven cards containing two triples choose the higher trips as the full-house trips;
- seven cards containing three pairs choose the two highest pairs and correct kicker;
- a six/seven-card flush chooses its five highest ranks;
- a straight chooses the highest available run;
- a flush is not incorrectly combined with off-suit straight cards into a straight flush, and the winning category is asserted to be `FLUSH`;
- a null list throws `NullPointerException`; null elements, duplicates, and lists of six/eight cards throw `IllegalArgumentException`.

## Exhaustive correctness oracle

`HandEvaluatorExhaustiveTest` iterates all `52 choose 5 = 2,598,960` distinct five-card combinations. It counts returned categories and asserts:

| Category | Expected count |
| --- | ---: |
| High card | 1,302,540 |
| One pair | 1,098,240 |
| Two pair | 123,552 |
| Three of a kind | 54,912 |
| Straight | 10,200 |
| Flush | 5,108 |
| Full house | 3,744 |
| Four of a kind | 624 |
| Straight flush | 40 |
| **Total** | **2,598,960** |

This test is deterministic and remains in the normal Maven suite. If runtime becomes material, optimize the private ranking path rather than weakening or disabling the oracle.

## Description vectors

Tests assert the exact description table in the architecture document for one representative of every category, plus `Five-high Straight`, `Five-high Straight Flush`, and `Ace-high Straight Flush`. Description tests are kept separate from comparison assertions so wording changes cannot hide ranking regressions. English pluralization is centralized in a private `HandRank` formatter rather than repeated across evaluator branches.

## Build checks

Run from the repository root:

```powershell
npm.cmd test
```

Equivalent server-only command:

```powershell
cd server
.\mvnw.cmd -B -ntp test
```

The implementation is complete only when the existing Spring context smoke test and every new engine test pass together.
