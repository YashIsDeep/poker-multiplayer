package com.poker.room;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RoomArchitectureTest {
    @Test
    void managerIsTransportNeutralAndPokerRoomDoesNotEscape() {
        assertFalse(Arrays.stream(RoomManager.class.getDeclaredMethods())
                .map(Method::getReturnType)
                .anyMatch(type -> type.getName().startsWith("com.poker.ws.")));
        assertFalse(Arrays.stream(RoomManager.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .anyMatch(type -> type.getName().startsWith("com.poker.ws.")));
        assertFalse(Arrays.stream(RoomManager.class.getAnnotations())
                .map(Annotation::annotationType)
                .anyMatch(type -> type.getName().startsWith("org.springframework")));
        assertFalse(Arrays.stream(PokerRoom.class.getDeclaredMethods())
                .map(Method::getReturnType)
                .anyMatch(type -> type.getName().startsWith("com.poker.ws.")));
    }
}
