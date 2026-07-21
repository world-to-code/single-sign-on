package com.example.sso.user.internal.group.domain;

import java.util.UUID;

/**
 * One group→role delegation, carrying the role's NAME rather than its id.
 *
 * <p>The name is what the assignment ceiling is expressed in ({@code mayAssignRoles} takes names), so
 * projecting it here saves the caller a second lookup per role just to turn an id back into the thing the
 * check actually reads.
 *
 * <p>An interface projection rather than a record, matching {@code IdName}: a JPQL constructor expression
 * would have to spell this type's fully-qualified name in the query string, which the codebase forbids
 * everywhere. Aliasing the selected columns to the accessor names avoids needing the type name at all.
 */
public interface GroupRoleName {

    UUID getGroupId();

    String getRoleName();
}
