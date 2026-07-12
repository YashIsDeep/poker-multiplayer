package com.poker.ws.protocol;

public enum CommandType {
    STATE_SYNC,
    ROOM_CREATE,
    ROOM_JOIN,
    ROOM_LEAVE,
    ROOM_SYNC,
    ROOM_TAKE_SEAT,
    ROOM_STAND,
    GAME_ACT,
    GAME_SIT_OUT,
    GAME_SIT_IN,
    GAME_REBUY
}
