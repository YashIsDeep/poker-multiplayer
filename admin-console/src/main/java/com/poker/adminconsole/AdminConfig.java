package com.poker.adminconsole;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

record AdminConfig(String url, String username, String password) {
    private static final String DATABASE_FILENAME = "poker.mv.db";
    private static final int CONNECTION_CHECK_TIMEOUT_MS = 250;

    static AdminConfig fromEnvironment() {
        Map<String, String> environment = System.getenv();
        String url = environment.get("ADMIN_DB_URL");
        if (url == null || url.isBlank()) {
            url = defaultUrl(environment);
        }

        return new AdminConfig(
                url,
                environment.getOrDefault("ADMIN_DB_USER", "sa"),
                environment.getOrDefault("ADMIN_DB_PASSWORD", "")
        );
    }

    private static String defaultUrl(Map<String, String> environment) {
        String host = firstNonBlank(environment.get("ADMIN_DB_HOST"), environment.get("DB_HOST"), "127.0.0.1");
        String port = firstNonBlank(environment.get("ADMIN_DB_PORT"), environment.get("DB_PORT"), "9092");

        if (isReachable(host, port)) {
            return "jdbc:h2:tcp://" + host + ":" + port + "/./data/poker";
        }

        Path repository = findRepository();
        Path productionDatabase = repository.resolve("data").resolve(DATABASE_FILENAME);
        Path developmentDatabase = repository.resolve("server").resolve("data").resolve(DATABASE_FILENAME);
        Path selectedDatabase = Files.isRegularFile(productionDatabase) ? productionDatabase : developmentDatabase;
        String databaseBase = removeDatabaseExtension(selectedDatabase);
        return "jdbc:h2:file:" + databaseBase.replace('\\', '/') + ";AUTO_SERVER=TRUE";
    }

    private static Path findRepository() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 5 && current != null; depth++, current = current.getParent()) {
            if (Files.isDirectory(current.resolve("server"))
                    && (Files.isDirectory(current.resolve("data")) || Files.isDirectory(current.resolve("server/data")))) {
                return current;
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private static boolean isReachable(String host, String portText) {
        try {
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65_535) {
                return false;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), CONNECTION_CHECK_TIMEOUT_MS);
                return true;
            }
        } catch (IOException | NumberFormatException ignored) {
            return false;
        }
    }

    private static String removeDatabaseExtension(Path database) {
        String path = database.toAbsolutePath().normalize().toString();
        return path.endsWith(".mv.db") ? path.substring(0, path.length() - ".mv.db".length()) : path;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalArgumentException("At least one value is required.");
    }
}
