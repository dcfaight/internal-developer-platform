package io.github.dcfaight.platformlab.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

class IdentityControllerTests {
    private final TeamAuthorization authorization = mock(TeamAuthorization.class);
    private final IdentityController controller = new IdentityController(authorization);
    private final TestingAuthenticationToken authentication =
            new TestingAuthenticationToken("admin", null, "ROLE_OPERATOR");
    private final JsonMapper mapper = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_EMPTY))
            .build();

    @ParameterizedTest
    @NullAndEmptySource
    void identityWithoutMembershipsAlwaysSerializesTeamsAsAnEmptyArray(List<String> teams) {
        when(authorization.teams(authentication)).thenReturn(teams);

        var identity = mapper.valueToTree(controller.me(authentication));

        assertEquals(mapper.readTree("""
                {"username":"admin","roles":["OPERATOR"],"teams":[]}
                """), identity);
    }

    @Test
    void identityPreservesMemberships() {
        when(authorization.teams(authentication)).thenReturn(List.of("team-a", "team-b"));

        var identity = mapper.valueToTree(controller.me(authentication));

        assertEquals(mapper.readTree("""
                {"username":"admin","roles":["OPERATOR"],"teams":["team-a","team-b"]}
                """), identity);
    }
}
