package com.example.sso.resource.internal.domain;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit test for {@link ResourceType#allows}: the member-kind constraint set membership check. */
class ResourceTypeTest {

    @Test
    void allowsOnlyTheConfiguredMemberKinds() {
        ResourceType type = new ResourceType("TEAM", Set.of(MemberType.GROUP, MemberType.APPLICATION));

        assertThat(type.allows(MemberType.GROUP)).isTrue();
        assertThat(type.allows(MemberType.APPLICATION)).isTrue();
        assertThat(type.allows(MemberType.USER)).isFalse();
        assertThat(type.allows(MemberType.RESOURCE)).isFalse();
    }
}
