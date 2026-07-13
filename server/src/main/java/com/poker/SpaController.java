package com.poker;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public final class SpaController {
    @GetMapping({"/", "/lobby", "/room/{roomId}"})
    public String forwardToClient() {
        return "forward:/index.html";
    }
}
