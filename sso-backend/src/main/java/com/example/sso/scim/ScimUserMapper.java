package com.example.sso.scim;

import com.example.sso.user.AppUser;
import de.captaingoldfish.scim.sdk.common.resources.User;
import de.captaingoldfish.scim.sdk.common.resources.complex.Meta;
import de.captaingoldfish.scim.sdk.common.resources.complex.Name;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Email;

import java.util.List;

/** Maps the domain {@link AppUser} to a SCIM {@link User} resource. */
public final class ScimUserMapper {

    private ScimUserMapper() {
    }

    public static User toScim(AppUser user) {
        User.UserBuilder builder = User.builder()
                .id(user.getId().toString())
                .userName(user.getUsername())
                .active(user.isEnabled())
                .emails(List.of(Email.builder()
                        .value(user.getEmail())
                        .type("work")
                        .primary(true)
                        .build()))
                .meta(Meta.builder()
                        .resourceType("User")
                        .created(user.getCreatedAt())
                        .lastModified(user.getUpdatedAt())
                        .location("/scim/v2/Users/" + user.getId())
                        .build());

        if (user.getExternalId() != null) {
            builder.externalId(user.getExternalId());
        }
        if (user.getDisplayName() != null) {
            builder.displayName(user.getDisplayName())
                    .name(Name.builder().formatted(user.getDisplayName()).build());
        }
        return builder.build();
    }
}
