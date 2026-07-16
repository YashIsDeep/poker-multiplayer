package com.poker.adminconsole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Read-only HTTP client for the game server's live-state admin API. The API
 * exists only while the server runs with poker.admin.live.enabled=true (the
 * dev profile) and only answers loopback callers.
 */
final class LiveServerClient {
    private static final String BASE_PATH = "/internal/admin/live";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String token;

    LiveServerClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    static LiveServerClient fromEnvironment() {
        Map<String, String> environment = System.getenv();
        String url = environment.getOrDefault("ADMIN_LIVE_URL", "http://127.0.0.1:8080").trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return new LiveServerClient(url, environment.get("ADMIN_LIVE_TOKEN"));
    }

    String baseUrl() {
        return baseUrl;
    }

    /** One-line connectivity status for the startup banner. Never throws. */
    String probe() {
        try {
            get("/overview");
            return "connected";
        } catch (LiveServerException exception) {
            return exception.getMessage();
        }
    }

    JsonNode overview() {
        return get("/overview");
    }

    JsonNode rooms() {
        return get("/rooms");
    }

    JsonNode room(String roomId) {
        return get("/rooms/" + roomId);
    }

    JsonNode connections() {
        return get("/connections");
    }

    JsonNode liveHand(String roomId) {
        return get("/rooms/" + roomId + "/hand");
    }

    JsonNode hands(String roomId) {
        return get("/rooms/" + roomId + "/hands");
    }

    JsonNode handDetail(String roomId, long handId) {
        return get("/rooms/" + roomId + "/hands/" + handId);
    }

    private JsonNode get(String path) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + BASE_PATH + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (token != null && !token.isBlank()) {
            request.header("X-Admin-Token", token.trim());
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new LiveServerException(
                    "The game server is not reachable at " + baseUrl
                            + ". Start it with 'npm run dev'.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LiveServerException("The live request was interrupted.", exception);
        }

        return switch (response.statusCode()) {
            case 200 -> parse(response.body());
            case 404 -> throw new LiveServerException(notFoundMessage(path));
            case 401, 403 -> throw new LiveServerException(disabledMessage(response.statusCode()));
            default -> throw new LiveServerException(
                    "The live admin API returned HTTP " + response.statusCode() + ".");
        };
    }

    private String notFoundMessage(String path) {
        if (path.matches("/rooms/[^/]+/hands/\\d+")) {
            return "No retained hand history has that id (the server keeps the last 20 hands per room).";
        }
        if (path.startsWith("/rooms/")) {
            return "No live room has that id.";
        }
        return disabledMessage(404);
    }

    private String disabledMessage(int status) {
        return "The live admin API refused the request (HTTP " + status
                + "). Run the server with the dev profile (poker.admin.live.enabled=true)"
                + " and make sure ADMIN_LIVE_TOKEN matches the server's token, if one is set.";
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException exception) {
            throw new LiveServerException("The live admin API returned malformed JSON.", exception);
        }
    }
}
