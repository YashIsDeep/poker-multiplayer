package com.poker.adminconsole;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Renders live-state admin API responses as terminal tables. */
final class LiveCommands {
    private final LiveServerClient client;

    LiveCommands(LiveServerClient client) {
        this.client = client;
    }

    /** One-line banner status, e.g. "http://127.0.0.1:8080 (connected)". */
    String status() {
        String probe = client.probe();
        return client.baseUrl() + " ("
                + ("connected".equals(probe) ? "connected" : "offline — " + probe) + ")";
    }

    void overview() {
        JsonNode node = client.overview();
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Server time", text(node, "serverTime")));
        rows.add(List.of("Uptime", humanDuration(node.path("uptimeSeconds").asLong())));
        rows.add(List.of("Rooms", text(node, "roomCount")));
        rows.add(List.of("Members", text(node, "memberCount")));
        rows.add(List.of("Seated players", text(node, "seatedCount")));
        rows.add(List.of("Connected sockets", text(node, "connectedSockets")));
        rows.add(List.of("Paused rooms", text(node, "pausedRoomCount")));
        rows.add(List.of("Lobby revision", text(node, "lobbyRevision")));
        TablePrinter.print(List.of("PROPERTY", "VALUE"), rows);
    }

    void rooms() {
        JsonNode node = client.rooms();
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode room : node) {
            rows.add(List.of(
                    text(room, "roomId"),
                    text(room, "joinCode"),
                    text(room, "name"),
                    room.path("privateTable").asBoolean() ? "yes" : "no",
                    room.path("seatedCount").asInt() + "/" + room.path("maxSeats").asInt(),
                    room.path("connectedCount").asInt() + "/" + room.path("memberCount").asInt(),
                    text(room, "tableStatus"),
                    text(room, "handId"),
                    text(room, "lastActivityAt")));
        }
        TablePrinter.print(
                List.of("ROOM_ID", "CODE", "NAME", "PRIVATE", "SEATED", "CONNECTED", "STATUS", "HAND", "LAST_ACTIVITY"),
                rows);
    }

    void room(String idOrJoinCode) {
        JsonNode room = client.room(resolveRoomId(idOrJoinCode));
        JsonNode config = room.path("config");
        JsonNode game = room.path("game");

        System.out.printf("Room:    %s (%s)%s%n",
                text(room, "name"),
                text(room, "joinCode"),
                room.path("privateTable").asBoolean() ? "  [private]" : "");
        System.out.printf("Id:      %s | creator %s%n", text(room, "roomId"), text(room, "creatorId"));
        System.out.printf("Times:   created %s | last activity %s | revision %s%n",
                text(room, "createdAt"), text(room, "lastActivityAt"), text(room, "revision"));
        System.out.printf("Rules:   blinds %s/%s | buy-in %s..%s | start %s | action %ss | rebuys %s%n",
                text(config, "smallBlind"), text(config, "bigBlind"),
                text(config, "minBuyIn"), text(config, "maxBuyIn"),
                text(config, "startingStack"), text(config, "actionTimeoutSeconds"),
                config.path("rebuysEnabled").asBoolean()
                        ? text(config, "rebuyStack") + " after " + text(config, "rebuyDelayHands") + " hands"
                        : "off");
        System.out.printf("Game:    %s | hand %s | actionSeq %s | deadline %s | next hand %s | safe boundary %s%n",
                text(game, "tableStatus"), text(game, "handId"), text(game, "actionSeq"),
                text(game, "deadline"), text(game, "nextHandAt"),
                game.path("safeBoundary").asBoolean() ? "yes" : "no");
        System.out.println();

        List<List<String>> rows = new ArrayList<>();
        for (JsonNode member : room.path("members")) {
            rows.add(List.of(
                    member.hasNonNull("seat") ? member.get("seat").asText() : "-",
                    text(member, "displayName"),
                    text(member, "userId"),
                    member.path("connected").asBoolean()
                            ? "yes"
                            : "no (since " + text(member, "disconnectedSince") + ")",
                    member.hasNonNull("stack") ? member.get("stack").asText() : "-",
                    text(member, "rebuyCount"),
                    text(member, "sockets"),
                    text(member, "joinedAt")));
        }
        TablePrinter.print(
                List.of("SEAT", "NAME", "USER_ID", "CONNECTED", "STACK", "REBUYS", "SOCKETS", "JOINED_AT"),
                rows);
    }

    void sockets() {
        JsonNode node = client.connections();
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode user : node.path("users")) {
            rows.add(List.of(
                    text(user, "userId"),
                    text(user, "sockets"),
                    user.hasNonNull("displayName") ? user.get("displayName").asText() : "-",
                    user.hasNonNull("roomId") ? user.get("roomId").asText() : "-"));
        }
        TablePrinter.print(List.of("USER_ID", "SOCKETS", "NAME", "ROOM_ID"), rows);
        System.out.println("Total sockets: " + node.path("totalSockets").asInt());
    }

    private String resolveRoomId(String idOrJoinCode) {
        try {
            return UUID.fromString(idOrJoinCode.trim()).toString();
        } catch (IllegalArgumentException ignored) {
            // Fall through and treat the argument as a join code.
        }
        String joinCode = idOrJoinCode.trim().toUpperCase(Locale.ROOT);
        for (JsonNode room : client.rooms()) {
            if (joinCode.equals(room.path("joinCode").asText().toUpperCase(Locale.ROOT))) {
                return room.path("roomId").asText();
            }
        }
        throw new LiveServerException("No live room has the join code " + joinCode + ".");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "-" : value.asText();
    }

    private static String humanDuration(long seconds) {
        Duration duration = Duration.ofSeconds(Math.max(0, seconds));
        if (duration.toHours() > 0) {
            return "%dh %02dm %02ds".formatted(duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
        }
        if (duration.toMinutes() > 0) {
            return "%dm %02ds".formatted(duration.toMinutes(), duration.toSecondsPart());
        }
        return duration.toSeconds() + "s";
    }
}
