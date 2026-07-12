package com.poker.ws.protocol;

import java.util.UUID;

public record CommandAck(
        String requestId,
        CommandType command,
        boolean ok,
        ErrorCode error,
        UUID roomId,
        Long roomRevision,
        Long lobbyRevision,
        StateSyncView state) {
    public static CommandAck success(
            String requestId,
            CommandType command,
            UUID roomId,
            long roomRevision,
            long lobbyRevision) {
        return new CommandAck(requestId, command, true, null, roomId, roomRevision, lobbyRevision, null);
    }

    public static CommandAck stateSuccess(String requestId, StateSyncView state) {
        return new CommandAck(
                requestId,
                CommandType.STATE_SYNC,
                true,
                null,
                state.room() == null ? null : state.room().roomId(),
                state.room() == null ? null : state.room().revision(),
                state.lobby().revision(),
                state);
    }

    public static CommandAck failure(String requestId, CommandType command, ErrorCode error) {
        return new CommandAck(requestId, command, false, error, null, null, null, null);
    }
}
