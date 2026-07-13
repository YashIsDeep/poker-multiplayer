package com.poker.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poker.room.RoomManager;
import com.poker.ws.protocol.CommandEnvelope;
import com.poker.ws.protocol.CreateRoomRequest;
import com.poker.ws.protocol.SyncStateRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class SessionWebSocketIntegrationTest {
    private static final String DATABASE = "ws_" + UUID.randomUUID().toString().replace("-", "");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    private WebSocketStompClient client;
    private StompSession session;
    private ThreadPoolTaskScheduler heartbeatScheduler;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE + ";DB_CLOSE_DELAY=-1");
        registry.add("poker.websocket.heartbeat-interval", () -> "1s");
    }

    @AfterEach
    void close() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        if (client != null) {
            client.stop();
        }
        if (heartbeatScheduler != null) {
            heartbeatScheduler.destroy();
        }
    }

    @Test
    void authenticatedSessionConnectsWithCsrfAndRecoversState() throws Exception {
        assertArrayEquals(new long[] {1_000, 1_000}, broker.getHeartbeatValue());
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<Void> login = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/auth/dev?u=ws-user"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(302, login.statusCode());
        String setCookie = login.headers().firstValue("set-cookie").orElseThrow();
        String sessionCookie = setCookie.substring(0, setCookie.indexOf(';'));

        HttpResponse<String> csrf = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/csrf"))
                        .header("Cookie", sessionCookie)
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csrf.statusCode());
        JsonNode csrfJson = objectMapper.readTree(csrf.body());
        String token = csrfJson.path("token").asText();
        assertTrue(token.length() > 10);

        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter(objectMapper));
        heartbeatScheduler = new ThreadPoolTaskScheduler();
        heartbeatScheduler.setThreadNamePrefix("stomp-test-heartbeat-");
        heartbeatScheduler.initialize();
        client.setTaskScheduler(heartbeatScheduler);
        client.setDefaultHeartbeat(new long[] {100, 100});
        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        httpHeaders.add("Cookie", sessionCookie);
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("X-XSRF-TOKEN", token);
        StompSessionHandlerAdapter sessionHandler = new StompSessionHandlerAdapter();
        session = client.connectAsync(
                        URI.create("ws://127.0.0.1:" + port + "/ws"),
                        httpHeaders,
                        connectHeaders,
                        sessionHandler)
                .get(10, TimeUnit.SECONDS);
        assertTrue(session.isConnected());

        Thread.sleep(1_500);
        assertTrue(session.isConnected(), "authenticated heartbeat must keep the STOMP session open");

        LinkedBlockingQueue<JsonNode> acks = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<JsonNode> roomSnapshots = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<JsonNode> lobbySnapshots = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/acks", jsonFrames(acks));
        session.subscribe("/user/queue/room", jsonFrames(roomSnapshots));
        session.subscribe("/topic/lobby", jsonFrames(lobbySnapshots));
        String requestId = UUID.randomUUID().toString();
        session.send("/app/state.sync", new CommandEnvelope<>(requestId, new SyncStateRequest()));
        JsonNode ackJson = acks.poll(10, TimeUnit.SECONDS);
        assertNotNull(ackJson);
        assertEquals(requestId, ackJson.path("requestId").asText());
        assertTrue(ackJson.path("ok").asBoolean());
        assertEquals(0, ackJson.path("state").path("lobby").path("rooms").size());

        session.send("/app/state.sync", Map.of("payload", Map.of()));
        JsonNode missingIdAck = acks.poll(10, TimeUnit.SECONDS);
        assertNotNull(missingIdAck);
        assertTrue(missingIdAck.path("requestId").isNull());
        assertFalse(missingIdAck.path("ok").asBoolean());
        assertEquals("INVALID_REQUEST", missingIdAck.path("error").asText());

        session.send("/app/state.sync", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "payload", Map.of(),
                "userId", UUID.randomUUID().toString()));
        JsonNode unknownFieldAck = acks.poll(10, TimeUnit.SECONDS);
        assertNotNull(unknownFieldAck);
        assertFalse(unknownFieldAck.path("ok").asBoolean());
        assertEquals("INVALID_REQUEST", unknownFieldAck.path("error").asText());

        String createRequestId = UUID.randomUUID().toString();
        session.send("/app/room.create", new CommandEnvelope<>(
                createRequestId, new CreateRoomRequest("Review Room", 2)));
        JsonNode createAck = acks.poll(10, TimeUnit.SECONDS);
        JsonNode roomSnapshot = roomSnapshots.poll(10, TimeUnit.SECONDS);
        JsonNode lobbySnapshot = lobbySnapshots.poll(10, TimeUnit.SECONDS);
        assertNotNull(createAck);
        assertNotNull(roomSnapshot);
        assertNotNull(lobbySnapshot);
        assertEquals(createRequestId, createAck.path("requestId").asText());
        assertTrue(createAck.path("ok").asBoolean());
        assertEquals(createAck.path("roomId"), roomSnapshot.path("roomId"));
        assertEquals(1, lobbySnapshot.path("rooms").size());

        HttpResponse<Void> logout = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/logout"))
                        .header("Cookie", sessionCookie)
                        .header("X-XSRF-TOKEN", token)
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(302, logout.statusCode());
        awaitDisconnected(session, Duration.ofSeconds(10));
        assertFalse(session.isConnected());
        assertEquals(1, roomManager.roomCount());
        assertFalse(roomManager.room(UUID.fromString(createAck.path("roomId").asText()))
                .orElseThrow().members().getFirst().connected());
        assertTrue(roomManager.lobby().rooms().isEmpty());
    }

    private static void awaitDisconnected(StompSession session, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (session.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static StompFrameHandler jsonFrames(LinkedBlockingQueue<JsonNode> queue) {
        return new StompFrameHandler() {
            @Override
            public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer((JsonNode) payload);
            }
        };
    }

    private static final class StompSessionHandlerAdapter
            extends org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter {
        @Override
        public void handleException(
                StompSession session,
                StompCommand command,
                StompHeaders headers,
                byte[] payload,
                Throwable exception) {
            throw new AssertionError("STOMP failure", exception);
        }

    }
}
