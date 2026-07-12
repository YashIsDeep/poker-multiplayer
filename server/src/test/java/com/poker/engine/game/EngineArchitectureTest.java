package com.poker.engine.game;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class EngineArchitectureTest {
    @Test
    void productionEngineDoesNotImportTransportPersistenceOrNondeterminism() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java", "com", "poker", "engine", "game");
        List<String> forbidden = List.of(
                "org.springframework", "com.fasterxml.jackson", "jakarta.persistence", "org.hibernate",
                "org.springframework.messaging", "java.net", "java.nio.file", "java.io", "java.time",
                "java.util.logging", "java.util.random", "Math.random", "System.currentTimeMillis",
                "System.nanoTime", "java.util.concurrent"
        );
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String source = Files.readString(path);
                    for (String token : forbidden) {
                        assertFalse(source.contains(token), () -> path + " contains forbidden dependency " + token);
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }
}
