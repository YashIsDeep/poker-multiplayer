package com.poker.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poker.auth.DevLoginTestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.session.timeout=3s", "poker.websocket.heartbeat-interval=1s"
})
@ActiveProfiles("dev")
class ExpiredSessionWebSocketIntegrationTest {
    private static final String DATABASE = "ws_expiry_" + UUID.randomUUID().toString().replace("-", "");
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcIndexedSessionRepository sessions;
    @Autowired private SimpMessagingTemplate messaging;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE + ";DB_CLOSE_DELAY=-1");
    }

    @Test
    void heartbeatDisabledPrivateSubscriptionClosesAtJdbcSessionExpiry() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String cookie = DevLoginTestClient.login(http, port, "expiry-" + UUID.randomUUID().toString().substring(0, 8));
        JsonNode me = getJson(http, cookie, "/api/me");
        String userId = me.path("id").asText();
        JsonNode csrf = getJson(http, cookie, "/api/csrf");
        Listener listener = new Listener();
        WebSocket socket = http.newWebSocketBuilder().header("Cookie", cookie)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), listener)
                .get(5, TimeUnit.SECONDS);
        try {
            socket.sendText("CONNECT\naccept-version:1.2\nheart-beat:0,0\nhost:localhost\nX-XSRF-TOKEN:"
                    + csrf.path("token").asText() + "\n\n\0", true).join();
            assertThat(listener.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            socket.sendText("SUBSCRIBE\nid:private\ndestination:/user/queue/room\n\n\0", true).join();

            String sessionId = new String(Base64.getDecoder().decode(cookie.substring(cookie.indexOf('=') + 1)),
                    StandardCharsets.UTF_8);
            long expiryDeadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (sessions.findById(sessionId) != null && System.nanoTime() < expiryDeadline) Thread.sleep(50);
            sessions.cleanUpExpiredSessions();
            assertThat(sessions.findById(sessionId)).isNull();
            assertThat(listener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);

            messaging.convertAndSendToUser(userId, "/queue/room", Map.of("secret", "must-not-arrive"));
            Thread.sleep(200);
            assertThat(listener.allFrames()).doesNotContain("must-not-arrive");
            HttpResponse<Void> unauthorized = http.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api/me")).header("Cookie", cookie).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(unauthorized.statusCode()).isEqualTo(401);
        } finally {
            socket.abort();
        }
    }

    private JsonNode getJson(HttpClient http, String cookie, String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private static final class Listener implements WebSocket.Listener {
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final StringBuilder all = new StringBuilder();
        private final StringBuilder pending = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            pending.append(data);
            if (last) {
                String frame = pending.toString();
                synchronized (all) { all.append(frame); }
                frames.offer(frame);
                pending.setLength(0);
            }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closed.complete(status);
            return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
        private String allFrames() { synchronized (all) { return all.toString(); } }
    }
}
