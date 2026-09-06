package com.poker.api;

import com.poker.auth.CurrentUserPrincipal;
import com.poker.auth.ModeratorPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {
    private final ModeratorPolicy moderators;

    public MeController(ModeratorPolicy moderators) {
        this.moderators = moderators;
    }

    @GetMapping("/api/me")
    public ResponseEntity<MeResponse> me(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CurrentUserPrincipal principal)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(new MeResponse(
                principal.userId(),
                principal.displayName(),
                principal.avatarUrl(),
                principal.provider(),
                moderators.isModerator(principal)));
    }
}
