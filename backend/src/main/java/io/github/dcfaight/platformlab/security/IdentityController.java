package io.github.dcfaight.platformlab.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdentityController {
    private final TeamAuthorization authorization;

    public IdentityController(TeamAuthorization authorization) {
        this.authorization = authorization;
    }

    @GetMapping("/api/v1/me")
    public IdentityResponse me(Authentication authentication) {
        return new IdentityResponse(authentication.getName(), authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .sorted().toList(), authorization.teams(authentication));
    }

    public record IdentityResponse(String username, List<String> roles,
            @JsonInclude(JsonInclude.Include.ALWAYS) List<String> teams) {
        public IdentityResponse {
            teams = teams == null ? List.of() : teams;
        }
    }
}
