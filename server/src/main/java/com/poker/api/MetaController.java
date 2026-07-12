package com.poker.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetaController {

    @GetMapping("/api/meta")
    public Map<String, String> meta() {
        return Map.of(
                "title", "HOLD'EM",
                "tagline", "multiplayer texas hold'em");
    }
}
