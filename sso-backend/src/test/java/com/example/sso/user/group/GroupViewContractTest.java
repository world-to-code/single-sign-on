package com.example.sso.user.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a group actually looks like on the wire.
 *
 * <p>This exists because a field silently left the response and nothing noticed. {@code roleNames} was a
 * record COMPONENT and became a derived accessor — Jackson serialises components, so the field simply stopped
 * being sent. The console read it unconditionally and every group page threw; the TypeScript interface still
 * declared it, so the frontend build stayed green, and no backend test asserted the response shape.
 *
 * <p>So the shape is asserted here rather than assumed: the console is a separate codebase whose types are
 * hand-written, and nothing else in either build compares the two.
 */
class GroupViewContractTest {

    private final ObjectMapper json = new ObjectMapper();

    private GroupView group() {
        return new GroupView("11111111-1111-1111-1111-111111111111", "Engineering", "desc", "ext",
                List.of("22222222-2222-2222-2222-222222222222"), 1, false,
                List.of(new GroupRole("33333333-3333-3333-3333-333333333333", "ROLE_USER")));
    }

    @Test
    void aGroupCarriesItsDelegatedRolesAsIdAndName() throws Exception {
        String wire = json.writeValueAsString(group());

        assertThat(wire).contains("\"roles\":[{\"id\":\"33333333-3333-3333-3333-333333333333\","
                + "\"name\":\"ROLE_USER\"}]");
    }

    /**
     * The id is what the console sends back. A name-only response would force it to round-trip names, which is
     * the resolution step that let a request be authorized against one role and bind another of the same name.
     */
    @Test
    void everyDelegatedRoleCarriesTheIdTheConsoleWritesBack() throws Exception {
        assertThat(json.readTree(json.writeValueAsString(group())).path("roles"))
                .allSatisfy(role -> {
                    assertThat(role.hasNonNull("id")).isTrue();
                    assertThat(role.hasNonNull("name")).isTrue();
                });
    }

    /** Derived, and deliberately not on the wire — the names are already inside {@code roles}. */
    @Test
    void theDerivedNameListIsNotASecondFieldOnTheWire() throws Exception {
        assertThat(json.writeValueAsString(group())).doesNotContain("roleNames");
        assertThat(group().roleNames()).containsExactly("ROLE_USER");
    }

    /** Every field the console reads, named here so removing one is a failing test rather than a blank page. */
    @Test
    void theResponseCarriesEveryFieldTheConsoleReads() throws Exception {
        assertThat(json.readTree(json.writeValueAsString(group())).fieldNames())
                .toIterable()
                .contains("id", "name", "description", "externalId", "memberUserIds", "memberCount",
                        "system", "roles");
    }
}
