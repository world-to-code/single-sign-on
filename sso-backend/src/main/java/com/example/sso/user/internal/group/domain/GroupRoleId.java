package com.example.sso.user.internal.group.domain;

import java.util.UUID;

/**
 * One group→role delegation, as a pair of ids.
 *
 * <p>Ids rather than the role's NAME, which is what this carried first. A name resolves org-first with a
 * global fallback, while the delegation itself points at a stored id — so a caller authorizing by name checks
 * a different role than the one membership actually confers, which is how a tenant grants itself a privileged
 * global role by minting a benign same-named local one. The assignment gate resolves by id for exactly that
 * reason; handing it a name would have reopened the hole it exists to close.
 *
 * <p>An interface projection rather than a record, matching {@code IdName}: a JPQL constructor expression
 * would have to spell this type's fully-qualified name in the query string, which the codebase forbids.
 */
public interface GroupRoleId {

    UUID getGroupId();

    UUID getRoleId();
}
