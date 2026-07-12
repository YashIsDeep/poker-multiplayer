package com.poker.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class JoinCodeTest {
    @Test
    void canonicalizesLocaleIndependently() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("ABC234", JoinCode.parse(" abc234 ").value());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void rejectsAmbiguousCharactersAndWrongLengths() {
        assertThrows(IllegalArgumentException.class, () -> JoinCode.parse("ABC01I"));
        assertThrows(IllegalArgumentException.class, () -> JoinCode.parse("ABCDE"));
        assertThrows(IllegalArgumentException.class, () -> JoinCode.parse("ABC DEF"));
        assertThrows(IllegalArgumentException.class, () -> JoinCode.parse("éBC234"));
    }
}
