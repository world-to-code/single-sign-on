package com.example.sso.scim.internal.application;

import com.example.sso.user.UserAccount;
import com.example.sso.user.RoleRef;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.resources.complex.Meta;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Member;

import java.util.List;

/** Maps a domain {@link RoleRef} (and its member users) to a SCIM {@link Group} resource. */
public final class ScimGroupMapper {

    private ScimGroupMapper() {
    }

    public static Group toScim(RoleRef role, List<UserAccount> members) {
        List<Member> scimMembers = members.stream()
                .map(user -> Member.builder()
                        .value(user.getId().toString())
                        .type("User")
                        .display(user.getUsername())
                        .build())
                .toList();
        return Group.builder()
                .id(role.getId().toString())
                .displayName(role.getName())
                .members(scimMembers)
                .meta(Meta.builder()
                        .resourceType("Group")
                        .location("/scim/v2/Groups/" + role.getId())
                        .build())
                .build();
    }
}
