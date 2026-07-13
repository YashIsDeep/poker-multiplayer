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
        assertThat(aliceView.potTotal()).isEqualTo(
                started.room().config().smallBlind()+started.room().config().bigBlind());
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

    @Test void playerSeatedAfterHandStartsIsNotDealtIntoThatHand() {
        UUID alice=UUID.randomUUID(),bob=UUID.randomUUID(),late=UUID.randomUUID();
        RoomChange created=rooms.create(profile(alice,"Alice"),"Late seat",3,RoomConfig.DEFAULT);
        rooms.join(profile(bob,"Bob"),created.room().joinCodeValue());
        rooms.join(profile(late,"Late"),created.room().joinCodeValue());
        rooms.takeSeat(alice,created.roomId(),0,1_000);
        RoomChange started=startScheduled(rooms.takeSeat(bob,created.roomId(),1,1_000));

        RoomChange lateSeated=rooms.takeSeat(late,created.roomId(),2,1_000);
        GameView view=new GameSnapshotRedactor().redact(lateSeated.room(),late);
        GameView.GameSeatView lateSeat=view.seats().stream()
                .filter(seat->seat.seat()==2).findFirst().orElseThrow();

        assertThat(lateSeated.room().game().presentedHand().players()).hasSize(2);
        assertThat(lateSeat.position()).isNull();
        assertThat(lateSeat.holeCards()).isNull();
        assertThat(started.room().game().handId()).isEqualTo(lateSeated.room().game().handId());
    }

    @Test void labelsSixHandedPositionsClockwiseFromTheButton() {
        UUID[] players=IntStream.range(0,6).mapToObj(ignored->UUID.randomUUID()).toArray(UUID[]::new);
        RoomChange frame=rooms.create(profile(players[0],"P0"),"Six max",6,RoomConfig.DEFAULT);
        for(int i=1;i<players.length;i++) rooms.join(profile(players[i],"P"+i),frame.room().joinCodeValue());
        for(int seat=0;seat<players.length;seat++) frame=rooms.takeSeat(players[seat],frame.roomId(),seat,500);

        GameView view=new GameSnapshotRedactor().redact(startScheduled(frame).room(),players[0]);
        assertThat(view.seats()).extracting(GameView.GameSeatView::seat,GameView.GameSeatView::position)
                .containsExactly(
                        tuple(0,"BN"),tuple(1,"SB"),tuple(2,"BB"),
                        tuple(3,"UTG"),tuple(4,"MP"),tuple(5,"CO"));
    }

    @Test void recordsRepeatActionsAndClearsThemWhenTheStreetChanges() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        RoomChange frame=rooms.create(profile(a,"A"),"Action history",3,RoomConfig.DEFAULT);
        rooms.join(profile(b,"B"),frame.room().joinCodeValue());
        rooms.join(profile(c,"C"),frame.room().joinCodeValue());
        rooms.takeSeat(a,frame.roomId(),0,500);
        rooms.takeSeat(b,frame.roomId(),1,500);
        frame=startScheduled(rooms.takeSeat(c,frame.roomId(),2,500));

        GameState game=frame.room().game();
        frame=rooms.act(actor(game),frame.roomId(),game.actionSeq(),GameAction.RAISE_TO,30L);
        game=frame.room().game();
        frame=rooms.act(actor(game),frame.roomId(),game.actionSeq(),GameAction.CALL,null);
        game=frame.room().game();
        frame=rooms.act(actor(game),frame.roomId(),game.actionSeq(),GameAction.RAISE_TO,60L);
        game=frame.room().game();
        frame=rooms.act(actor(game),frame.roomId(),game.actionSeq(),GameAction.CALL,null);
        game=frame.room().game();
        frame=rooms.act(actor(game),frame.roomId(),game.actionSeq(),GameAction.CALL,null);

        GameView preflop=new GameSnapshotRedactor().redact(frame.room(),a);
        assertThat(preflop.seats().stream().filter(seat->seat.seat()==0).findFirst().orElseThrow().actions())
                .extracting(GameView.LastActionView::action).containsExactly("RAISE_TO","CALL");
        assertThat(preflop.seats().stream().filter(seat->seat.seat()==1).findFirst().orElseThrow().actions())
                .extracting(GameView.LastActionView::action).containsExactly("CALL","CALL");

        game=frame.room().game();
        frame=rooms.advancePresentation(frame.roomId(),game.handId(),game.scheduleGeneration()).orElseThrow();
        GameView flop=new GameSnapshotRedactor().redact(frame.room(),a);
        assertThat(flop.phase()).isEqualTo("FLOP");
        assertThat(flop.seats()).allMatch(seat->seat.actions().isEmpty()&&seat.lastAction()==null);

        game=frame.room().game();
        frame=rooms.act(actor(game),frame.roomId(),game.actionSeq(),GameAction.CHECK,null);
        GameView afterCheck=new GameSnapshotRedactor().redact(frame.room(),a);
        assertThat(afterCheck.seats().stream().filter(seat->seat.seat()==1).findFirst().orElseThrow().actions())
                .extracting(GameView.LastActionView::action).containsExactly("CHECK");
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

    @Test void leaveAndReturnRestoresLastTableBalance() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        RoomChange frame=rooms.create(profile(a,"A"),"Balance ledger",2,RoomConfig.DEFAULT);
        rooms.join(profile(b,"B"),frame.room().joinCodeValue());
        rooms.takeSeat(a,frame.roomId(),0,500);
        frame=startScheduled(rooms.takeSeat(b,frame.roomId(),1,500));

        GameState game=frame.room().game();
        UUID returningUser=actor(game);
        frame=advanceToComplete(rooms,rooms.act(
                returningUser,frame.roomId(),game.actionSeq(),GameAction.FOLD,null));
        TableSeatState finalSeat=frame.room().game().seats().stream()
                .filter(seat->seat.userId().equals(returningUser)).findFirst().orElseThrow();
        long lastBalance=finalSeat.stack();
        String joinCode=frame.room().joinCodeValue();

        rooms.leave(returningUser,frame.roomId());
        rooms.join(profile(returningUser,"Returning"),joinCode);
        RoomChange returned=rooms.takeSeat(returningUser,frame.roomId(),finalSeat.seat(),1_000);

        assertThat(returned.room().game().seats().stream()
                .filter(seat->seat.userId().equals(returningUser)).findFirst().orElseThrow().stack())
                .isEqualTo(lastBalance)
                .isNotEqualTo(1_000);
    }

    @Test void bustedPlayerLeavesTableAndWaitsConfiguredHandsBeforeRebuying() {
        RoomManager table=new RoomManager(clock,new JoinCodeGenerator(new java.util.Random(11)),
                ()->deckWithPrefix("2c","As","Kh","2d","Ah","Qh","3c","4c","5d","7h","8c","9s","Tc","Jd"));
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        RoomConfig config=RoomConfig.table(10,200,true,200,3);
        RoomChange frame=table.create(profile(a,"A"),"Cooldown",3,config,true);
        table.join(profile(b,"B"),frame.room().joinCodeValue());
        table.join(profile(c,"C"),frame.room().joinCodeValue());
        table.takeSeat(a,frame.roomId(),0,200);
        table.takeSeat(b,frame.roomId(),1,200);
        frame=startScheduled(table,table.takeSeat(c,frame.roomId(),2,200));

        GameState game=frame.room().game();
        frame=table.act(actor(game),frame.roomId(),game.actionSeq(),GameAction.RAISE_TO,200L);
        game=frame.room().game();
        frame=table.act(actor(game),frame.roomId(),game.actionSeq(),GameAction.FOLD,null);
        game=frame.room().game();
        frame=table.act(actor(game),frame.roomId(),game.actionSeq(),GameAction.CALL,null);
        frame=advanceToComplete(table,frame);

        TableSeatState busted=frame.room().game().seats().stream()
                .filter(seat->seat.status()==TableSeatStatus.BUSTED).findFirst().orElseThrow();
        UUID bustedUser=busted.userId();
        UUID roomId=frame.roomId();
        assertThat(busted.rebuyHandsRemaining()).isEqualTo(3);
        GameView bustedView=new GameSnapshotRedactor().redact(frame.room(),bustedUser);
        assertThat(bustedView.viewer().rebuyAmount()).isEqualTo(200);
        assertThat(bustedView.viewer().rebuyHandsRemaining()).isEqualTo(3);

        int bustedSeat=busted.seat();
        GameState completed=frame.room().game();
        frame=table.nextHand(roomId,completed.handId(),completed.scheduleGeneration()).orElseThrow();
        assertThat(frame.room().member(bustedUser).seat()).isNull();
        assertThat(frame.room().seats().get(bustedSeat)).isNull();
        assertThat(frame.room().game().players().get(bustedUser).rebuyHandsRemaining()).isEqualTo(3);

        assertThatThrownBy(()->table.rebuy(bustedUser,roomId,200))
                .isInstanceOf(GameException.class)
                .extracting(e->((GameException)e).error()).isEqualTo(GameError.REBUY_NOT_AVAILABLE);

        for(int handsRemaining=2;handsRemaining>=0;handsRemaining--){
            frame=startScheduled(table,frame);
            game=frame.room().game();
            frame=table.act(actor(game),frame.roomId(),game.actionSeq(),GameAction.FOLD,null);
            frame=advanceToComplete(table,frame);
            assertThat(frame.room().game().players().get(bustedUser).rebuyHandsRemaining())
                    .isEqualTo(handsRemaining);
            completed=frame.room().game();
            frame=table.nextHand(roomId,completed.handId(),completed.scheduleGeneration()).orElseThrow();
        }

        table.updateSettings(a,roomId,RoomConfig.table(10,200,true,350,3));
        assertThatThrownBy(()->table.rebuy(bustedUser,roomId,200))
                .isInstanceOf(GameException.class)
                .extracting(e->((GameException)e).error()).isEqualTo(GameError.INVALID_BUY_IN);
        RoomChange rebought=table.rebuy(bustedUser,roomId,350);
        assertThat(rebought.room().game().players().get(bustedUser))
                .extracting(TablePlayerState::stack,TablePlayerState::rebuyHandsRemaining,
                        TablePlayerState::rebought)
                .containsExactly(350L,0,true);
        RoomChange reseated=table.takeSeat(bustedUser,roomId,bustedSeat,200);
        assertThat(reseated.room().game().seats().stream()
                .filter(seat->seat.userId().equals(bustedUser)).findFirst().orElseThrow().stack())
                .isEqualTo(350);
    }

    @Test void loneFundedPlayerStartsFiveSecondOldestBustedRebuyQueue() {
        RoomManager table=new RoomManager(clock,new JoinCodeGenerator(new java.util.Random(13)),
                ()->deckWithPrefix("2c","As","Kh","2d","Ah","Qh","3c","4c","5d","7h","8c","9s","Tc","Jd"));
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        RoomConfig config=RoomConfig.table(10,200,true,200,10);
        RoomChange frame=table.create(profile(a,"A"),"Emergency rebuy",3,config,true);
        UUID roomId=frame.roomId();
        table.join(profile(b,"B"),frame.room().joinCodeValue());
        table.join(profile(c,"C"),frame.room().joinCodeValue());
        table.takeSeat(a,frame.roomId(),0,200);
        table.takeSeat(b,frame.roomId(),1,200);
        frame=startScheduled(table,table.takeSeat(c,frame.roomId(),2,200));

        GameState game=frame.room().game();
        frame=table.act(actor(game),frame.roomId(),game.actionSeq(),GameAction.RAISE_TO,200L);
        game=frame.room().game();
        frame=table.act(actor(game),frame.roomId(),game.actionSeq(),GameAction.CALL,null);
        game=frame.room().game();
        frame=table.act(actor(game),frame.roomId(),game.actionSeq(),GameAction.CALL,null);
        frame=advanceToComplete(table,frame);

        List<UUID> bustedByAge=frame.room().game().players().entrySet().stream()
                .filter(entry->entry.getValue().stack()==0)
                .sorted(Comparator.comparingLong(entry->entry.getValue().bustedOrder()))
                .map(Map.Entry::getKey).toList();
        assertThat(bustedByAge).hasSize(2);
        GameState completed=frame.room().game();
        frame=table.nextHand(frame.roomId(),completed.handId(),completed.scheduleGeneration()).orElseThrow();

        assertThat(frame.room().seatedCount()).isEqualTo(1);
        assertThat(frame.room().game().rebuyOfferUserId()).isEqualTo(bustedByAge.get(0));
        assertThat(frame.room().game().rebuyOfferExpiresAt()).isEqualTo(clock.instant().plusSeconds(5));

        GameState firstOffer=frame.room().game();
        clock.advance(Duration.ofSeconds(5));
        frame=table.rotateEmergencyRebuyOffer(
                frame.roomId(),firstOffer.scheduleGeneration(),bustedByAge.get(0)).orElseThrow();
        assertThat(frame.room().game().rebuyOfferUserId()).isEqualTo(bustedByAge.get(1));

        assertThatThrownBy(()->table.rebuy(bustedByAge.get(0),roomId,200))
                .isInstanceOf(GameException.class)
                .extracting(error->((GameException)error).error())
                .isEqualTo(GameError.REBUY_NOT_AVAILABLE);
        RoomChange rebought=table.rebuy(bustedByAge.get(1),frame.roomId(),200);
        assertThat(rebought.room().game().players().get(bustedByAge.get(1)).stack()).isEqualTo(200);
        assertThat(rebought.room().game().rebuyOfferUserId()).isNull();
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
        return startScheduled(rooms,waiting);
    }

    private RoomChange startScheduled(RoomManager manager,RoomChange waiting) {
        GameState game=waiting.room().game();
        clock.advance(Duration.ofSeconds(2));
        return manager.startHand(
                waiting.roomId(),game.lifecycleSeq(),game.scheduleGeneration()).orElseThrow();
    }

    private static RoomChange advanceToComplete(RoomManager manager,RoomChange frame){
        while(frame.room().game().presentedHand().phase()!=HandPhase.COMPLETE){
            GameState game=frame.room().game();
            frame=manager.advancePresentation(
                    frame.roomId(),game.handId(),game.scheduleGeneration()).orElseThrow();
        }
        return frame;
    }

    private static Deck deckWithPrefix(String... cards){
        List<Card> ordered=new ArrayList<>();
        for(String card:cards) ordered.add(Card.parse(card));
        for(int code=0;code<52;code++){
            Card card=new Card(code);
            if(!ordered.contains(card)) ordered.add(card);
        }
        return Deck.of(ordered);
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
