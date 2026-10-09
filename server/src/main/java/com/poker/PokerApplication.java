package com.poker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PokerApplication {

	public static void main(String[] args) {
		bindH2AutoServerToLoopback();
		SpringApplication.run(PokerApplication.class, args);
	}

	/**
	 * The file datasource uses {@code AUTO_SERVER=TRUE} so local tools such as
	 * Admin Console can share the database with a running server. H2 starts that
	 * helper with {@code -tcpAllowOthers} and, without a bind address, listens on
	 * every interface — advertising a LAN address in {@code data/poker.lock.db}.
	 * Confine it to loopback, which is the only reach those local tools need.
	 *
	 * <p>H2 reads this property once from a static initializer, so it must be set
	 * before any H2 class loads. An explicit value is left alone so an operator
	 * can still choose a different address deliberately.</p>
	 */
	static void bindH2AutoServerToLoopback() {
		if (System.getProperty("h2.bindAddress") == null) {
			System.setProperty("h2.bindAddress", "127.0.0.1");
		}
	}

}
