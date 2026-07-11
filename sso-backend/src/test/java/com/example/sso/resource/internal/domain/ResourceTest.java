package com.example.sso.resource.internal.domain;

import com.example.sso.resource.internal.graph.application.ResourceGraphService;

import com.example.sso.shared.error.BadRequestException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Local member-kind invariants of the {@link Resource} aggregate — now pure validation methods that
 * take the type's allowed kinds as an argument (the kinds live in explicit
 * {@code resource_type_allowed_member} rows, loaded by the service). Self-loop and cycle rejection are
 * graph-reachability concerns enforced by {@code ResourceGraphService} and covered by its tests.
 */
class ResourceTest {

    private final ResourceType team = new ResourceType("TEAM", null);
    private final Set<MemberType> teamAllows =
            Set.of(MemberType.RESOURCE, MemberType.GROUP, MemberType.APPLICATION);

    @Test
    void allowsNestingWhenTheTypePermitsResourceMembers() {
        Resource dev = new Resource("Dev", team, null);

        assertThatCode(() -> dev.requireCanNest(teamAllows)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNestingWhenTheTypeDisallowsIt() {
        Resource parent = new Resource("Flat", new ResourceType("FLAT", null), null);

        assertThatThrownBy(() -> parent.requireCanNest(Set.of(MemberType.GROUP)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void enforcesMemberKindConstraints() {
        Resource dev = new Resource("Dev", team, null);

        assertThatCode(() -> dev.requireCanAttachMember(MemberType.GROUP, teamAllows)).doesNotThrowAnyException();
        assertThatCode(() -> dev.requireCanAttachMember(MemberType.APPLICATION, teamAllows)).doesNotThrowAnyException();
        assertThatThrownBy(() -> dev.requireCanAttachMember(MemberType.USER, teamAllows))
                .isInstanceOf(BadRequestException.class); // TEAM does not allow USER
    }

    @Test
    void rejectsResourceKindAsLeafMember() {
        Resource dev = new Resource("Dev", team, null);

        assertThatThrownBy(() -> dev.requireCanAttachMember(MemberType.RESOURCE, teamAllows))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void groupAndUserMemberIdsMustBeUuids() {
        assertThatThrownBy(() -> new ResourceMember(MemberType.GROUP, "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceMember(MemberType.USER, "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ResourceMember(MemberType.APPLICATION, "client-abc")).doesNotThrowAnyException();
        assertThatCode(() -> ResourceMember.user(UUID.randomUUID())).doesNotThrowAnyException();
    }
}
