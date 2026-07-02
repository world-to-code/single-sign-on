package com.example.sso.resource.internal.domain;

import com.example.sso.shared.error.BadRequestException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Local invariants of the {@link Resource} aggregate (global cycle checks live in the graph service). */
class ResourceTest {

    private final ResourceType team = new ResourceType("TEAM",
            Set.of(MemberType.RESOURCE, MemberType.GROUP, MemberType.APPLICATION));

    @Test
    void rejectsSelfAsChild() {
        Resource dev = new Resource("Dev", team);

        assertThatThrownBy(() -> dev.addChild(dev)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsChildWhenTypeDisallowsNesting() {
        ResourceType flat = new ResourceType("FLAT", Set.of(MemberType.GROUP));
        Resource parent = new Resource("Flat", flat);

        assertThatThrownBy(() -> parent.addChild(new Resource("Sub", flat)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void enforcesMemberKindConstraints() {
        Resource dev = new Resource("Dev", team);

        dev.attachMember(ResourceMember.group(UUID.randomUUID()));      // allowed
        dev.attachMember(ResourceMember.application("client-1"));       // allowed
        assertThatThrownBy(() -> dev.attachMember(ResourceMember.user(UUID.randomUUID())))
                .isInstanceOf(BadRequestException.class);               // TEAM does not allow USER

        assertThat(dev.getMembers()).hasSize(2);
    }

    @Test
    void rejectsResourceKindAsLeafMember() {
        Resource dev = new Resource("Dev", team);

        assertThatThrownBy(() -> dev.attachMember(new ResourceMember(MemberType.RESOURCE, "x")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void removeChildAndDetachMemberUndoTheirAttach() {
        Resource dev = new Resource("Dev", team);
        Resource sub = new Resource("Sub", team);
        dev.addChild(sub);
        dev.removeChild(sub);
        assertThat(dev.getChildren()).isEmpty();

        ResourceMember group = ResourceMember.group(UUID.randomUUID());
        dev.attachMember(group);
        dev.attachMember(group); // duplicate attach — set semantics, no double entry
        assertThat(dev.getMembers()).hasSize(1);
        dev.detachMember(group);
        assertThat(dev.getMembers()).isEmpty();
    }

    @Test
    void grantAndRevokeByUserAndTier() {
        Resource dev = new Resource("Dev", team);
        UUID lead = UUID.randomUUID();

        dev.grant(ResourceGrant.admin(lead));
        dev.grant(ResourceGrant.viewer(lead));
        assertThat(dev.getGrants()).hasSize(2);

        dev.revoke(lead, ResourceRoleTier.VIEWER);
        assertThat(dev.getGrants()).containsExactly(ResourceGrant.admin(lead));
    }

    @Test
    void regrantingSameUserAndTierReplacesInsteadOfDuplicating() {
        // The DB PK is (resource, user, tier) but the record's identity also includes roleId —
        // grant() must replace, or two rows with the same PK would coexist and blow up on flush.
        Resource dev = new Resource("Dev", team);
        UUID lead = UUID.randomUUID();
        UUID catalogRole = UUID.randomUUID();

        dev.grant(ResourceGrant.admin(lead));
        dev.grant(new ResourceGrant(lead, ResourceRoleTier.ADMIN, catalogRole));

        assertThat(dev.getGrants()).containsExactly(new ResourceGrant(lead, ResourceRoleTier.ADMIN, catalogRole));
    }

    @Test
    void groupAndUserMemberIdsMustBeUuids() {
        assertThatThrownBy(() -> new ResourceMember(MemberType.GROUP, "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceMember(MemberType.USER, "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ResourceMember(MemberType.APPLICATION, "client-abc").memberId()).isEqualTo("client-abc");
    }
}
