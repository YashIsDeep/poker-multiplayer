package com.poker.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poker.auth.DevLoginTestClient;
import com.poker.presence.UserSocketRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "poker.abuse.max-sockets-per-user=2", "poker.abuse.max-sockets-per-http-session=2",
        "poker.abuse.max-subscriptions-per-socket=2", "poker.abuse.lifecycle-frame-burst=4",
        "poker.abuse.lifecycle-frame-rate=0.01", "poker.abuse.heartbeat-frame-burst=4",
        "poker.abuse.heartbeat-frame-rate=0.01"
})
@ActiveProfiles("dev")
class WebSocketResourceIntegrationTest {
    private static final String DATABASE = "ws_bounds_" + UUID.randomUUID().toString().replace("-", "");
    @LocalServerPort private int port;
    @Autowired private WebSocketTransportRegistry transports;
    @Autowired private SubscriptionOwnershipInterceptor subscriptions;
    @Autowired private UserSocketRegistry presence;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<WebSocket> sockets = new ArrayList<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE
                + ";DB_CLOSE_DELAY=-1;QUERY_STATISTICS=TRUE");
    }

    @AfterEach
    void cleanup() throws Exception {
        for (WebSocket socket : sockets) socket.abort();
        awaitClean();
    }

    @Test
    void excessPhysicalConnectionClosesWithoutAnyStompConnect() throws Exception {
        String cookie = login();
        open(cookie);
        open(cookie);
        RawSocket excess = open(cookie);
        assertThat(excess.listener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        assertThat(transports.trackedSocketCount()).isEqualTo(2);
        assertThat(presence.total()).isZero();
    }

    @Test
    void duplicateSubscriptionActuallyClosesNetworkTransportAndCleansIndexes() throws Exception {
        RawSocket raw = connected();
        raw.socket.sendText("SUBSCRIBE\nid:first\ndestination:/topic/lobby\n\n\0"
                + "SUBSCRIBE\nid:second\ndestination:/topic/lobby\n\n\0", true).join();
        assertThat(raw.listener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        awaitClean();
    }

    @Test
    void unsubscribeFloodActuallyClosesNetworkTransport() throws Exception {
        RawSocket raw = connected();
        StringBuilder frames = new StringBuilder();
        for (int i = 0; i < 20; i++) frames.append("UNSUBSCRIBE\nid:unknown-").append(i).append("\n\n\0");
        raw.socket.sendText(frames, true).join();
        assertThat(raw.listener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        awaitClean();
    }

    @Test
    void heartbeatFloodIsBoundedBeforeSessionDatabaseValidationAndClosesTransport() throws Exception {
        RawSocket raw = connected();
        jdbc.execute("SET QUERY_STATISTICS FALSE");
        jdbc.execute("SET QUERY_STATISTICS TRUE");
        for (int i = 0; i < 250 && !raw.listener.closed.isDone(); i++) {
            try {
                raw.socket.sendText("\n", true).join();
            } catch (java.util.concurrent.CompletionException closedDuringFlood) {
                break;
            }
        }

        assertThat(raw.listener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        Long sessionStatements = jdbc.queryForObject(
                "select coalesce(sum(execution_count), 0) from information_schema.query_statistics "
                        + "where upper(sql_statement) like '%SPRING_SESSION%'",
                Long.class);
        assertThat(sessionStatements).isNotNull().isLessThan(50L);
        awaitClean();
    }

    private String login() throws Exception { return DevLoginTestClient.login(http, port, "bounds-" + UUID.randomUUID().toString().substring(0, 8)); }

    private RawSocket connected() throws Exception {
        String cookie = login();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/csrf")).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String token = new ObjectMapper().readTree(response.body()).path("token").asText();
        RawSocket raw = open(cookie);
        raw.socket.sendText("CONNECT\naccept-version:1.2\nhost:localhost\nX-XSRF-TOKEN:" + token + "\n\n\0", true).join();
        assertThat(raw.listener.frames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
        return raw;
    }

    private RawSocket open(String cookie) throws Exception {
        Listener listener = new Listener();
        WebSocket socket = http.newWebSocketBuilder().header("Cookie", cookie).connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), listener).get(5, TimeUnit.SECONDS);
        sockets.add(socket);
        return new RawSocket(socket, listener);
    }

    private void awaitClean() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while ((transports.trackedSocketCount() != 0 || subscriptions.trackedSocketCount() != 0 || presence.total() != 0)
                && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(transports.trackedSocketCount()).isZero();
        assertThat(subscriptions.trackedSocketCount()).isZero();
        assertThat(presence.total()).isZero();
    }

    private record RawSocket(WebSocket socket, Listener listener) {}
    private static final class Listener implements WebSocket.Listener {
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final StringBuilder pending = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            pending.append(data);
            if (last) { frames.offer(pending.toString()); pending.setLength(0); }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closed.complete(status);
            return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
    }
}
