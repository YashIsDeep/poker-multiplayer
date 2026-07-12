package com.poker.room;

import static org.assertj.core.api.Assertions.*;
import com.poker.engine.cards.*;
import com.poker.engine.game.*;
import com.poker.ws.GameSnapshotRedactor;
import com.poker.ws.protocol.GameView;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GameplayWiringTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-13T00:00:00Z"));
    private final RoomManager rooms = new RoomManager(clock, new JoinCodeGenerator(new java.util.Random(7)),
            () -> Deck.of(IntStream.range(0,52).mapToObj(Card::new).toList()));

    @Test void startsHandRedactsCardsAndRejectsStaleReplay() {
        UUID alice=UUID.randomUUID(), bob=UUID.randomUUID(), spectator=UUID.randomUUID();
        RoomChange created=rooms.create(profile(alice,"Alice"),"Table",3,RoomConfig.DEFAULT);
        rooms.join(profile(bob,"Bob"),created.room().joinCodeValue());
        rooms.join(profile(spectator,"Watcher"),created.room().joinCodeValue());
        rooms.takeSeat(alice,created.roomId(),0,1_000);
        RoomChange waiting=rooms.takeSeat(bob,created.roomId(),1,1_000);
        assertThat(waiting.room().game().tableStatus()).isEqualTo(TableStatus.WAITING);
        RoomChange started=startScheduled(waiting);
        assertThat(started.room().game().tableStatus()).isEqualTo(TableStatus.RUNNING);
        assertThat(started.room().game().handId()).isEqualTo(1);
        GameSnapshotRedactor redactor=new GameSnapshotRedactor();
        GameView aliceView=redactor.redact(started.room(),alice);
        GameView bobView=redactor.redact(started.room(),bob);
        GameView spectatorView=redactor.redact(started.room(),spectator);
        GameView unknownView=redactor.redact(started.room(),null);
        assertThat(aliceView.seats().stream().filter(s->s.seat()==0).findFirst().orElseThrow().holeCards()).hasSize(2);
        assertThat(aliceView.seats().stream().filter(s->s.seat()==1).findFirst().orElseThrow().holeCards()).isNull();
        assertThat(bobView.seats().stream().filter(s->s.seat()==1).findFirst().orElseThrow().holeCards()).hasSize(2);
        assertThat(spectatorView.seats()).allMatch(s->s.holeCards()==null);
        assertThat(unknownView.seats()).allMatch(s->s.holeCards()==null);
        assertThat(unknownView.viewer().canAct()).isFalse();
        assertThat(unknownView.viewer().legalActions()).isNull();

        GameState game=started.room().game();
        int actorSeat=game.presentedHand().actingSeat().orElseThrow();
        UUID actor=game.seats().stream().filter(s->s.seat()==actorSeat).findFirst().orElseThrow().userId();
        LegalActions legal=game.legalActions();
        GameAction action=legal.canCheck()?GameAction.CHECK:GameAction.CALL;
        assertThatThrownBy(()->rooms.act(actor,started.roomId(),game.actionSeq(),action,1L))
                .isInstanceOf(GameException.class)
                .extracting(e->((GameException)e).error()).isEqualTo(GameError.ACTION_NOT_LEGAL);
        assertThat(rooms.room(started.roomId()).orElseThrow().revision()).isEqualTo(started.roomRevision());
        RoomChange accepted=rooms.act(actor,started.roomId(),game.actionSeq(),action,null);
        assertThat(accepted.roomRevision()).isGreaterThan(started.roomRevision());
        assertThatThrownBy(()->rooms.act(actor,started.roomId(),game.actionSeq(),action,null))
                .isInstanceOf(GameException.class).extracting(e->((GameException)e).error()).isEqualTo(GameError.STALE_ACTION);
    }

    @Test void validatesBuyInAndQueuesStandDuringHand() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();RoomChange created=rooms.create(profile(a,"A"),"T",2,RoomConfig.DEFAULT);rooms.join(profile(b,"B"),created.room().joinCodeValue());
        assertThatThrownBy(()->rooms.takeSeat(a,created.roomId(),0,100)).isInstanceOf(RoomException.class);
        rooms.takeSeat(a,created.roomId(),0,500);
        RoomChange waiting=rooms.takeSeat(b,created.roomId(),1,500);
        startScheduled(waiting);
        RoomChange queued=rooms.stand(a,created.roomId());
        assertThat(queued.room().member(a).seat()).isEqualTo(0);
        assertThat(queued.room().game().seats().stream().filter(s->s.userId().equals(a)).findFirst().orElseThrow().standAfterHand()).isTrue();
    }

    @Test void repeatedSeatRequestMustUseOriginalBuyIn() {
        UUID user=UUID.randomUUID();
        RoomChange created=rooms.create(profile(user,"A"),"T",2,RoomConfig.DEFAULT);
        rooms.takeSeat(user,created.roomId(),0,500);
        assertThat(rooms.takeSeat(user,created.roomId(),0,500).room().member(user).seat()).isEqualTo(0);
        assertThatThrownBy(()->rooms.takeSeat(user,created.roomId(),0,600))
                .isInstanceOf(RoomException.class)
                .extracting(e->((RoomException)e).error()).isEqualTo(RoomError.ALREADY_SEATED);
    }

    @Test void stalePresentationGenerationCannotSkipRunoutFrames() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        RoomChange created=rooms.create(profile(a,"A"),"T",2,RoomConfig.DEFAULT);
        rooms.join(profile(b,"B"),created.room().joinCodeValue());
        rooms.takeSeat(a,created.roomId(),0,200);
        RoomChange started=startScheduled(rooms.takeSeat(b,created.roomId(),1,200));

        GameState first=started.room().game();
        UUID firstActor=actor(first);
        long raiseTo=first.legalActions().maximumRaiseTo();
        RoomChange shoved=rooms.act(firstActor,created.roomId(),first.actionSeq(),
                GameAction.RAISE_TO,raiseTo);
        assertThat(new GameSnapshotRedactor().redact(shoved.room(),firstActor).seats().stream()
                .filter(seat->seat.userId().equals(firstActor)).findFirst().orElseThrow()
                .lastAction().amount()).isEqualTo(raiseTo);
        GameState second=shoved.room().game();
        UUID secondActor=actor(second);
        RoomChange called=rooms.act(secondActor,created.roomId(),second.actionSeq(),GameAction.CALL,null);
        GameState pacing=called.room().game();
        assertThat(pacing.deadline()).isNull();
        long generation=pacing.scheduleGeneration();

        RoomChange advanced=rooms.advancePresentation(created.roomId(),pacing.handId(),generation).orElseThrow();
        assertThat(rooms.advancePresentation(created.roomId(),pacing.handId(),generation)).isEmpty();
        assertThat(rooms.room(created.roomId()).orElseThrow().revision()).isEqualTo(advanced.roomRevision());

        List<Integer> boardSizes=new ArrayList<>();
        RoomChange frame=advanced;
        boardSizes.add(frame.room().game().presentedHand().board().size());
        while(frame.room().game().presentedHand().phase()!=HandPhase.COMPLETE){
            GameState current=frame.room().game();
            long currentGeneration=current.scheduleGeneration();
            frame=rooms.advancePresentation(
                    created.roomId(),current.handId(),currentGeneration).orElseThrow();
            assertThat(rooms.advancePresentation(
                    created.roomId(),current.handId(),currentGeneration)).isEmpty();
            boardSizes.add(frame.room().game().presentedHand().board().size());
        }
        assertThat(boardSizes).containsSubsequence(3,4,5);
        assertThat(frame.room().game().presentedHand().players())
                .extracting(PlayerSnapshot::stack).satisfies(stacks ->
                        assertThat(stacks.stream().mapToLong(Long::longValue).sum()).isEqualTo(400));
        assertThat(new GameSnapshotRedactor().redact(frame.room(),null).seats())
                .allMatch(seat->seat.holeCards()!=null&&seat.holeCards().size()==2);
    }

    @Test void timeoutIsGuardedAndQueuesTheActorToSitOut() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        RoomChange created=rooms.create(profile(a,"A"),"T",2,RoomConfig.DEFAULT);
        rooms.join(profile(b,"B"),created.room().joinCodeValue());
        rooms.takeSeat(a,created.roomId(),0,500);
        RoomChange started=startScheduled(rooms.takeSeat(b,created.roomId(),1,500));
        GameState game=started.room().game();
        UUID actor=actor(game);

        assertThat(rooms.timeout(created.roomId(),game.handId(),game.actionSeq(),actor)).isEmpty();
        clock.advance(Duration.ofSeconds(30));
        RoomChange timedOut=rooms.timeout(
                created.roomId(),game.handId(),game.actionSeq(),actor).orElseThrow();
        assertThat(timedOut.room().game().seats().stream()
                .filter(seat->seat.userId().equals(actor)).findFirst().orElseThrow().sitOutAfterHand())
                .isTrue();
        assertThat(rooms.timeout(created.roomId(),game.handId(),game.actionSeq(),actor)).isEmpty();
    }

    @Test void failedEngineStartQuarantinesTableWithoutCorruptingSeatLedger() {
        RoomManager failing=new RoomManager(clock,new JoinCodeGenerator(new java.util.Random(9)),
                ()->{throw new IllegalStateException("deck unavailable");});
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        RoomChange created=failing.create(profile(a,"A"),"T",2,RoomConfig.DEFAULT);
        failing.join(profile(b,"B"),created.room().joinCodeValue());
        failing.takeSeat(a,created.roomId(),0,500);
        RoomChange waiting=failing.takeSeat(b,created.roomId(),1,500);
        GameState game=waiting.room().game();
        clock.advance(Duration.ofSeconds(2));

        RoomChange failed=failing.startHand(
                created.roomId(),game.lifecycleSeq(),game.scheduleGeneration()).orElseThrow();
        assertThat(failed.room().game().tableStatus()).isEqualTo(TableStatus.FAILED);
        assertThat(failed.room().game().seats()).extracting(TableSeatState::stack)
                .containsExactly(500L,500L);
        assertThat(failed.room().members()).allMatch(MemberState::seated);
    }

    @Test void foldWinNeverRevealsOpponentCards() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        RoomChange created=rooms.create(profile(a,"A"),"T",2,RoomConfig.DEFAULT);
        rooms.join(profile(b,"B"),created.room().joinCodeValue());
        rooms.takeSeat(a,created.roomId(),0,500);
        RoomChange started=startScheduled(rooms.takeSeat(b,created.roomId(),1,500));
        GameState game=started.room().game();
        UUID actor=actor(game);
        RoomChange frame=rooms.act(actor,created.roomId(),game.actionSeq(),GameAction.FOLD,null);
        while(frame.room().game().presentedHand().phase()!=HandPhase.COMPLETE){
            GameState current=frame.room().game();
            frame=rooms.advancePresentation(
                    created.roomId(),current.handId(),current.scheduleGeneration()).orElseThrow();
        }

        GameView winnerView=new GameSnapshotRedactor().redact(frame.room(),
                actor.equals(a)?b:a);
        assertThat(winnerView.seats().stream()
                .filter(seat->seat.userId().equals(actor)).findFirst().orElseThrow().holeCards())
                .isNull();
        assertThat(new GameSnapshotRedactor().redact(frame.room(),null).seats())
                .allMatch(seat->seat.holeCards()==null);
    }

    private static UUID actor(GameState game){
        int seat=game.presentedHand().actingSeat().orElseThrow();
        return game.seats().stream().filter(s->s.seat()==seat).findFirst().orElseThrow().userId();
    }

    private RoomChange startScheduled(RoomChange waiting) {
        GameState game=waiting.room().game();
        clock.advance(Duration.ofSeconds(2));
        return rooms.startHand(
                waiting.roomId(),game.lifecycleSeq(),game.scheduleGeneration()).orElseThrow();
    }

    private static MemberProfile profile(UUID id,String name){return new MemberProfile(id,name,null);}

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now){this.now=now;}
        private void advance(Duration duration){now=now.plus(duration);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
}
