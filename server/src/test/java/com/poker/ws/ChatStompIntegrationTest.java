package com.poker.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ChatStompIntegrationTest {
    private static final String DATABASE = "chat_ws_" + UUID.randomUUID().toString().replace("-", "");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final List<StompSession> sessions = new ArrayList<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE + ";DB_CLOSE_DELAY=-1");
    }

    @AfterEach
    void close() {
        sessions.forEach(session -> {
            if (session.isConnected()) session.disconnect();
        });
        clients.forEach(WebSocketStompClient::stop);
    }

    @Test
    void deliversLiveMessagesToRoomMembersWithoutAddingThemToSnapshots() throws Exception {
        Peer alice = connect("chat-alice");
        Peer bob = connect("chat-bob");
        Peer outsider = connect("chat-outsider");

        send(alice, "/app/room.create", Map.of("name", "Chat Room", "maxSeats", 2));
        JsonNode createAck = await(alice.acks, command("ROOM_CREATE"));
        assertThat(createAck.path("ok").asBoolean()).isTrue();
        String roomId = createAck.path("roomId").asText();
        String joinCode = await(alice.rooms, node -> node.path("roomId").asText().equals(roomId))
                .path("joinCode").asText();

        send(bob, "/app/room.join", Map.of("joinCode", joinCode));
        assertThat(await(bob.acks, command("ROOM_JOIN")).path("ok").asBoolean()).isTrue();

        send(alice, "/app/room.chat", Map.of("roomId", roomId, "message", "  hello table  "));
        JsonNode chatAck = await(alice.acks, command("CHAT_SEND"));
        assertThat(chatAck.path("ok").asBoolean()).isTrue();

        JsonNode aliceMessage = await(alice.chat, node -> node.path("message").asText().equals("hello table"));
        JsonNode bobMessage = await(bob.chat, node -> node.path("message").asText().equals("hello table"));
        assertThat(aliceMessage.path("roomId").asText()).isEqualTo(roomId);
        assertThat(bobMessage.path("senderName").asText()).isEqualTo("chat-alice");

        send(outsider, "/app/room.chat", Map.of("roomId", roomId, "message", "not allowed"));
        JsonNode outsiderAck = await(outsider.acks, command("CHAT_SEND"));
        assertThat(outsiderAck.path("ok").asBoolean()).isFalse();
        assertThat(outsiderAck.path("error").asText()).isEqualTo("NOT_A_ROOM_MEMBER");

        send(alice, "/app/state.sync", Map.of());
        JsonNode stateAck = await(alice.acks, command("STATE_SYNC"));
        assertThat(stateAck.path("state").path("room").has("chat")).isFalse();
    }

    private Peer connect(String user) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<Void> login = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/auth/dev?u=" + user))
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());
        String cookie = login.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        HttpResponse<String> csrf = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/csrf"))
                        .header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String token = objectMapper.readTree(csrf.body()).path("token").asText();

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter(objectMapper));
        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        httpHeaders.add("Cookie", cookie);
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("X-XSRF-TOKEN", token);
        StompSession session = client.connectAsync(
                        URI.create("ws://127.0.0.1:" + port + "/ws"),
                        httpHeaders,
                        connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        Peer peer = new Peer(session);
        session.subscribe("/user/queue/acks", frames(peer.acks));
        session.subscribe("/user/queue/room", frames(peer.rooms));
        session.subscribe("/user/queue/chat", frames(peer.chat));
        clients.add(client);
        sessions.add(session);
        return peer;
    }

    private static void send(Peer peer, String destination, Map<String, ?> payload) {
        peer.session.send(destination, Map.of(
                "requestId", UUID.randomUUID().toString(),
                "payload", payload));
    }

    private static Predicate<JsonNode> command(String value) {
        return node -> node.path("command").asText().equals(value);
    }

    private static JsonNode await(BlockingQueue<JsonNode> queue, Predicate<JsonNode> wanted)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode node = queue.poll(250, TimeUnit.MILLISECONDS);
            if (node != null && wanted.test(node)) return node;
        }
        throw new AssertionError("timed out waiting for STOMP frame");
    }

    private static StompFrameHandler frames(BlockingQueue<JsonNode> queue) {
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

    private static final class Peer {
        private final StompSession session;
        private final BlockingQueue<JsonNode> acks = new LinkedBlockingQueue<>();
        private final BlockingQueue<JsonNode> rooms = new LinkedBlockingQueue<>();
        private final BlockingQueue<JsonNode> chat = new LinkedBlockingQueue<>();

        private Peer(StompSession session) {
            this.session = session;
        }
    }
}
