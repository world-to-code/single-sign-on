package com.example.sso.admin;

import com.example.sso.admin.internal.user.application.UserDetailAdminService;
import com.example.sso.admin.internal.user.application.UserDetailView;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.deny.UserDeny;
import com.example.sso.user.rbac.Permissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB proof that the user-detail console is deny-aware: a permission a user is GRANTED but a deny removes
 * appears in {@code deniedPermissions} (the "why is this permission absent" answer) and NOT in
 * {@code effectivePermissions}. The unit test mocks the resolver; this drives the real deny-resolution chain.
 */
class UserDetailDenyIT extends AbstractIntegrationTest {

    @Autowired
    UserDetailAdminService userDetail;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void aGrantRemovedByADenyShowsInDeniedNotEffective() {
        UUID userId = newGlobalUser();
        grantDirect(userId, Permissions.USER_READ);
        userDeny(userId, Permissions.USER_READ); // a USER-level deny beats the same-level direct grant

        UserDetailView detail = userDetail.getUser(userId);

        assertThat(detail.deniedPermissions()).contains(Permissions.USER_READ);
        assertThat(detail.effectivePermissions()).doesNotContain(Permissions.USER_READ);
        assertThat(detail.directPermissions()).contains(Permissions.USER_READ); // still granted, just denied
        // the USER-level deny is listed with an id so the console can lift it
        assertThat(detail.userDenies()).extracting(UserDeny::pattern).containsExactly(Permissions.USER_READ);
    }

    private UUID newGlobalUser() {
        UUID id = UUID.randomUUID();
        String name = "detail-deny-" + id.toString().substring(0, 8);
        ownerJdbc().update("insert into app_user (id, username, email, enabled) values (?, ?, ?, true)",
                id, name, name + "@example.com");
        cleanups.add(() -> ownerJdbc().update("delete from app_user where id = ?", id)); // cascades grants/denies
        return id;
    }

    private void grantDirect(UUID userId, String permission) {
        ownerJdbc().update("insert into permission (id, name) values (gen_random_uuid(), ?) "
                + "on conflict (name) do nothing", permission);
        ownerJdbc().update("insert into app_user_permission (user_id, permission_id) "
                + "select ?, id from permission where name = ?", userId, permission);
    }

    private void userDeny(UUID userId, String pattern) {
        ownerJdbc().update("insert into app_user_permission_deny (id, user_id, org_id, pattern) "
                + "values (gen_random_uuid(), ?, null, ?)", userId, pattern);
    }
}
