package com.poker.api;

import com.poker.ranked.RankedResults;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class RankedController {
    private final RankedResults results;
    public RankedController(RankedResults results) { this.results = results; }

    @GetMapping("/api/leaderboard")
    public RankedResults.Board leaderboard(@RequestParam(defaultValue="0") int page,
                                           @RequestParam(defaultValue="50") int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return results.leaderboard(page, pageSize);
    }

    @GetMapping("/api/players/{userId}")
    public RankedResults.Player player(@PathVariable UUID userId) {
        return results.player(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
