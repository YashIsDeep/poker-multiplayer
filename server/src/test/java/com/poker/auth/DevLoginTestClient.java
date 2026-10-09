package com.poker.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** Uses the real anonymous CSRF bootstrap and session rotation for socket tests. */
public final class DevLoginTestClient {
    private DevLoginTestClient() {}

    public static String login(HttpClient http, int port, String name) throws Exception {
        String base = "http://127.0.0.1:" + port;
        HttpResponse<String> bootstrap = http.send(
                HttpRequest.newBuilder(URI.create(base + "/auth/dev/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (bootstrap.statusCode() != 200) throw new AssertionError("CSRF bootstrap failed: " + bootstrap.statusCode());
        String cookie = bootstrap.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        var csrf = new ObjectMapper().readTree(bootstrap.body());
        String headerName = csrf.path("headerName").asText();
        String token = csrf.path("token").asText();
        if (headerName.isBlank() || token.isBlank()) throw new AssertionError("CSRF bootstrap response was incomplete");
        HttpResponse<Void> login = http.send(HttpRequest.newBuilder(URI.create(base + "/auth/dev"))
                .header("Cookie", cookie).header(headerName, token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("u=" + URLEncoder.encode(name, StandardCharsets.UTF_8))).build(),
                HttpResponse.BodyHandlers.discarding());
        if (login.statusCode() != 302) throw new AssertionError("Guest login failed: " + login.statusCode());
        return login.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }
}
