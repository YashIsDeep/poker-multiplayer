package com.poker.api;

import com.poker.ranked.RankedResults;
import com.poker.ranked.RankedReadBudget;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class RankedController {
    private final RankedResults results;
    private final RankedReadBudget budget;
    public RankedController(RankedResults results, RankedReadBudget budget) {
        this.results = results;
        this.budget = budget;
    }

    @GetMapping("/api/leaderboard")
    public RankedResults.Board leaderboard(@RequestParam(defaultValue="0") int page,
                                           @RequestParam(defaultValue="50") int pageSize,
                                           HttpServletResponse response) {
        if (page < 0 || pageSize < 1 || pageSize > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        limit(response);
        return results.leaderboard(page, pageSize);
    }

    @GetMapping("/api/players/{userId}")
    public RankedResults.Player player(@PathVariable UUID userId, HttpServletResponse response) {
        limit(response);
        return results.player(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void limit(HttpServletResponse response) {
        if (!budget.acquire()) {
            response.setHeader("Retry-After", "1");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Statistics request limit reached");
        }
    }
}
