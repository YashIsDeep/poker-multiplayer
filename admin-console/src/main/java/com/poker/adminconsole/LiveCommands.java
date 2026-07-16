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

    void liveHand(String idOrJoinCode) {
        JsonNode hand = client.liveHand(resolveRoomId(idOrJoinCode));
        if (!hand.path("active").asBoolean()) {
            System.out.printf("No hand is being presented. Table status: %s | last hand id %s | next hand at %s%n",
                    text(hand, "tableStatus"), text(hand, "handId"), text(hand, "nextHandAt"));
            return;
        }

        System.out.printf("Hand #%s | %s%s | board %s | pot %s | table %s%n",
                text(hand, "handId"),
                text(hand, "phase"),
                hand.hasNonNull("completionReason") ? " (" + text(hand, "completionReason") + ")" : "",
                cardList(hand.path("board")),
                text(hand, "potTotal"),
                text(hand, "tableStatus"));
        System.out.printf("Bets:    current bet %s | last full raise %s | chips in play %s%n",
                text(hand, "currentBet"), text(hand, "lastFullRaiseSize"), text(hand, "totalChipsAtStart"));
        System.out.println("Acting:  " + actingLine(hand));
        System.out.println();

        List<List<String>> rows = new ArrayList<>();
        for (JsonNode player : hand.path("players")) {
            String state = player.path("folded").asBoolean() ? "folded"
                    : player.path("allIn").asBoolean() ? "all-in" : "active";
            rows.add(List.of(
                    text(player, "seat"),
                    text(player, "displayName"),
                    player.hasNonNull("role") ? player.get("role").asText() : "",
                    cardList(player.path("holeCards")),
                    text(player, "stack"),
                    text(player, "streetCommitted"),
                    text(player, "handCommitted"),
                    state));
        }
        TablePrinter.print(
                List.of("SEAT", "NAME", "ROLE", "CARDS", "STACK", "STREET_BET", "HAND_BET", "STATE"),
                rows);

        JsonNode pots = hand.path("pots");
        if (pots.size() > 1 || hand.hasNonNull("pendingUnmatched")) {
            System.out.println();
            List<List<String>> potRows = new ArrayList<>();
            for (JsonNode pot : pots) {
                potRows.add(List.of(
                        text(pot, "index"), text(pot, "amount"), text(pot, "cap"),
                        joinInts(pot.path("eligibleSeats"))));
            }
            TablePrinter.print(List.of("POT", "AMOUNT", "CAP", "ELIGIBLE_SEATS"), potRows);
            JsonNode pending = hand.path("pendingUnmatched");
            if (!pending.isMissingNode() && !pending.isNull()) {
                System.out.printf("Unmatched bet: %s from seat %s%n",
                        text(pending, "amount"), text(pending, "seat"));
            }
        }
        printAwards(hand.path("awards"));
    }

    void hands(String idOrJoinCode) {
        JsonNode node = client.hands(resolveRoomId(idOrJoinCode));
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode hand : node) {
            rows.add(List.of(
                    text(hand, "handId"),
                    hand.path("inProgress").asBoolean() ? "live" : "done",
                    text(hand, "phase"),
                    hand.hasNonNull("completionReason") ? hand.get("completionReason").asText() : "-",
                    cardList(hand.path("board")),
                    text(hand, "potAwarded"),
                    hand.hasNonNull("winners") ? hand.get("winners").asText() : "-",
                    text(hand, "playerCount"),
                    text(hand, "completedAt")));
        }
        TablePrinter.print(
                List.of("HAND", "STATE", "PHASE", "REASON", "BOARD", "POT", "WINNERS", "PLAYERS", "COMPLETED_AT"),
                rows);
        System.out.println("The server retains the last 20 hands per room. Use 'hand <room> <hand-id>' for the full event log.");
    }

    void handDetail(String idOrJoinCode, long handId) {
        JsonNode hand = client.handDetail(resolveRoomId(idOrJoinCode), handId);

        System.out.printf("Hand #%s (%s) | blinds %s/%s | started %s | completed %s%n",
                text(hand, "handId"),
                hand.path("inProgress").asBoolean() ? "in progress" : "complete",
                text(hand, "smallBlind"), text(hand, "bigBlind"),
                text(hand, "startedAt"), text(hand, "completedAt"));
        System.out.printf("Board:   %s%n", cardList(hand.path("finalBoard")));
        System.out.println();

        List<List<String>> playerRows = new ArrayList<>();
        for (JsonNode player : hand.path("players")) {
            playerRows.add(List.of(
                    text(player, "seat"),
                    text(player, "displayName"),
                    text(player, "userId"),
                    text(player, "startingStack")));
        }
        TablePrinter.print(List.of("SEAT", "NAME", "USER_ID", "STARTING_STACK"), playerRows);
        System.out.println();

        List<List<String>> eventRows = new ArrayList<>();
        for (JsonNode event : hand.path("events")) {
            eventRows.add(List.of(
                    text(event, "sequence"),
                    timeOnly(text(event, "occurredAt")),
                    text(event, "phase"),
                    text(event, "description")));
        }
        TablePrinter.print(List.of("SEQ", "TIME", "PHASE", "EVENT"), eventRows);
        printAwards(hand.path("awards"));
    }

    private void printAwards(JsonNode awards) {
        if (awards.isMissingNode() || awards.isEmpty()) return;
        System.out.println();
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode award : awards) {
            StringBuilder winners = new StringBuilder();
            for (JsonNode winner : award.path("winners")) {
                if (!winners.isEmpty()) winners.append(", ");
                winners.append("seat ").append(text(winner, "seat"))
                        .append(" (").append(text(winner, "displayName")).append(") +")
                        .append(text(winner, "amount"));
            }
            rows.add(List.of(text(award, "potIndex"), text(award, "potAmount"), winners.toString()));
        }
        TablePrinter.print(List.of("POT", "AMOUNT", "WINNERS"), rows);
    }

    private static String actingLine(JsonNode hand) {
        if (!hand.hasNonNull("actingSeat")) {
            return "nobody (between streets or hand complete)";
        }
        StringBuilder line = new StringBuilder("seat " + hand.get("actingSeat").asText());
        JsonNode legal = hand.path("legalActions");
        if (!legal.isMissingNode() && !legal.isNull()) {
            List<String> options = new ArrayList<>();
            if (legal.path("canFold").asBoolean()) options.add("fold");
            if (legal.path("canCheck").asBoolean()) options.add("check");
            if (legal.path("canCall").asBoolean()) options.add("call " + legal.path("callAmount").asText());
            if (legal.path("canRaise").asBoolean()) {
                options.add("raise " + legal.path("minimumRaiseTo").asText()
                        + ".." + legal.path("maximumRaiseTo").asText());
            }
            line.append(" | options: ").append(String.join(" / ", options));
        }
        if (hand.hasNonNull("deadline")) {
            line.append(" | deadline ").append(hand.get("deadline").asText());
        }
        return line.toString();
    }

    private static String cardList(JsonNode cards) {
        if (cards.isMissingNode() || cards.isEmpty()) return "-";
        List<String> values = new ArrayList<>();
        cards.forEach(card -> values.add(card.asText()));
        return String.join(" ", values);
    }

    private static String joinInts(JsonNode numbers) {
        List<String> values = new ArrayList<>();
        numbers.forEach(number -> values.add(number.asText()));
        return String.join(",", values);
    }

    private static String timeOnly(String instant) {
        int t = instant.indexOf('T');
        return t < 0 ? instant : instant.substring(t + 1);
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
