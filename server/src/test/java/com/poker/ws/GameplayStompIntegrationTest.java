package com.poker.ws;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GameplayStompIntegrationTest {
 private static final String DB="game_ws_"+UUID.randomUUID().toString().replace("-","");
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->"jdbc:h2:mem:"+DB+";DB_CLOSE_DELAY=-1");}
 @LocalServerPort int port; @Autowired ObjectMapper mapper;
 private final List<WebSocketStompClient> clients=new ArrayList<>(); private final List<StompSession> sessions=new ArrayList<>();
 @AfterEach void close(){sessions.forEach(s->{if(s.isConnected())s.disconnect();});clients.forEach(WebSocketStompClient::stop);}

 @Test void twoClientsPlayWithViewerPrivateCardsAndStaleActionGuard() throws Exception {
   Peer alice=connect("game-alice"),bob=connect("game-bob");
   send(alice,"/app/room.create",Map.of("name","Heads Up","maxSeats",2,"privateTable",true,"smallBlind",5,"bigBlind",10,"minBuyIn",200,"maxBuyIn",2000,"actionTimeoutSeconds",30));
   JsonNode create=await(alice.acks,n->n.path("command").asText().equals("ROOM_CREATE"));assertThat(create.path("ok").asBoolean()).isTrue();String roomId=create.path("roomId").asText();
   JsonNode created=await(alice.rooms,n->n.path("roomId").asText().equals(roomId));String code=created.path("joinCode").asText();
   send(bob,"/app/room.join",Map.of("joinCode",code));assertThat(await(bob.acks,n->n.path("command").asText().equals("ROOM_JOIN")).path("ok").asBoolean()).isTrue();
   send(alice,"/app/room.take-seat",Map.of("roomId",roomId,"seat",0,"buyIn",1000));assertThat(await(alice.acks,n->n.path("command").asText().equals("ROOM_TAKE_SEAT")).path("ok").asBoolean()).isTrue();
   send(bob,"/app/room.take-seat",Map.of("roomId",roomId,"seat",1,"buyIn",1000));assertThat(await(bob.acks,n->n.path("command").asText().equals("ROOM_TAKE_SEAT")).path("ok").asBoolean()).isTrue();
   JsonNode a=await(alice.rooms,n->n.path("game").path("handId").asLong()==1);JsonNode b=await(bob.rooms,n->n.path("game").path("handId").asLong()==1);
   assertThat(seat(a,0).path("holeCards").size()).isEqualTo(2);assertThat(seat(a,1).path("holeCards").isNull()).isTrue();
   assertThat(seat(b,1).path("holeCards").size()).isEqualTo(2);assertThat(seat(b,0).path("holeCards").isNull()).isTrue();
   JsonNode actorView=a.path("game").path("actingSeat").asInt()==0?a:b;Peer actor=a.path("game").path("actingSeat").asInt()==0?alice:bob;
   long seq=actorView.path("game").path("actionSeq").asLong();String action=actorView.path("game").path("toCall").asLong()>0?"call":"check";
   Map<String,Object> payload=new HashMap<>();payload.put("roomId",roomId);payload.put("actionSeq",seq);payload.put("action",action);payload.put("amount",null);
   send(actor,"/app/game.act",payload);JsonNode accepted=await(actor.acks,n->n.path("command").asText().equals("GAME_ACT"));assertThat(accepted.path("ok").asBoolean()).isTrue();assertThat(accepted.path("actionSeq").asLong()).isGreaterThan(seq);
   send(actor,"/app/game.act",payload);JsonNode stale=await(actor.acks,n->n.path("command").asText().equals("GAME_ACT"));assertThat(stale.path("ok").asBoolean()).isFalse();assertThat(stale.path("error").asText()).isEqualTo("STALE_ACTION");
   actor.session.disconnect();
   Peer reconnected=connect(actor.user);
   send(reconnected,"/app/state.sync",Map.of());
   JsonNode synced=await(reconnected.acks,n->n.path("command").asText().equals("STATE_SYNC"));
   assertThat(synced.path("ok").asBoolean()).isTrue();
   assertThat(synced.path("state").path("room").path("roomId").asText()).isEqualTo(roomId);
   assertThat(synced.path("state").path("room").path("game").path("seats"))
           .anySatisfy(seat->assertThat(seat.path("holeCards").size()).isEqualTo(2));
 }

 private Peer connect(String user)throws Exception{HttpClient http=HttpClient.newHttpClient();HttpResponse<Void> login=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/auth/dev?u="+user)).GET().build(),HttpResponse.BodyHandlers.discarding());String cookie=login.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0];HttpResponse<String> csrf=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/csrf")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());String token=mapper.readTree(csrf.body()).path("token").asText();WebSocketStompClient client=new WebSocketStompClient(new StandardWebSocketClient());client.setMessageConverter(new MappingJackson2MessageConverter(mapper));WebSocketHttpHeaders wh=new WebSocketHttpHeaders();wh.add("Cookie",cookie);StompHeaders sh=new StompHeaders();sh.add("X-XSRF-TOKEN",token);StompSession session=client.connectAsync(URI.create("ws://127.0.0.1:"+port+"/ws"),wh,sh,new org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter(){}).get(10,TimeUnit.SECONDS);Peer peer=new Peer(user,session);session.subscribe("/user/queue/acks",frames(peer.acks));session.subscribe("/user/queue/room",frames(peer.rooms));clients.add(client);sessions.add(session);return peer;}
 private void send(Peer p,String destination,Map<String,?> payload){p.session.send(destination,Map.of("requestId",UUID.randomUUID().toString(),"payload",payload));}
 private static JsonNode seat(JsonNode snapshot,int seat){for(JsonNode s:snapshot.path("game").path("seats"))if(s.path("seat").asInt()==seat)return s;throw new AssertionError("missing seat");}
 private static JsonNode await(BlockingQueue<JsonNode> queue,Predicate<JsonNode> wanted)throws InterruptedException{long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(System.nanoTime()<end){JsonNode n=queue.poll(250,TimeUnit.MILLISECONDS);if(n!=null&&wanted.test(n))return n;}throw new AssertionError("timed out waiting for frame");}
 private static StompFrameHandler frames(BlockingQueue<JsonNode> q){return new StompFrameHandler(){public java.lang.reflect.Type getPayloadType(StompHeaders h){return JsonNode.class;}public void handleFrame(StompHeaders h,Object p){q.offer((JsonNode)p);}};}
 private static final class Peer{final String user;final StompSession session;final BlockingQueue<JsonNode> acks=new LinkedBlockingQueue<>(),rooms=new LinkedBlockingQueue<>();Peer(String user,StompSession s){this.user=user;session=s;}}
}
