package com.poker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The production datasource opens the file database with {@code AUTO_SERVER=TRUE}.
 * H2 starts that helper server with {@code -tcpAllowOthers}, so without a bind
 * address it accepts connections on every interface rather than loopback only.
 */
class PokerApplicationH2BindAddressTest {
    private static final String BIND_ADDRESS = "h2.bindAddress";

    private String original;

    @BeforeEach
    void captureProperty() {
        original = System.getProperty(BIND_ADDRESS);
    }

    @AfterEach
    void restoreProperty() {
        if (original == null) {
            System.clearProperty(BIND_ADDRESS);
        } else {
            System.setProperty(BIND_ADDRESS, original);
        }
    }

    @Test
    void confinesTheAutoServerToLoopbackWhenNothingIsConfigured() {
        System.clearProperty(BIND_ADDRESS);

        PokerApplication.bindH2AutoServerToLoopback();

        assertEquals("127.0.0.1", System.getProperty(BIND_ADDRESS));
    }

    @Test
    void keepsAnAddressTheOperatorChoseDeliberately() {
        System.setProperty(BIND_ADDRESS, "10.0.0.5");

        PokerApplication.bindH2AutoServerToLoopback();

        assertEquals("10.0.0.5", System.getProperty(BIND_ADDRESS));
    }
}
