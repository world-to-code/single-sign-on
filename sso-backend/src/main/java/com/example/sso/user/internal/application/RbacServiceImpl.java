package com.example.sso.user.internal.application;

import com.example.sso.user.internal.domain.Permission;
import com.example.sso.user.Permissions;
import com.example.sso.user.RbacService;
import com.example.sso.user.internal.domain.Role;
import com.example.sso.user.internal.domain.RoleRepository;
import com.example.sso.user.internal.domain.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Default {@link RbacService}: manages the permission catalog (PBAC) and its assignment to roles. */
@Service
@RequiredArgsConstructor
public class RbacServiceImpl implements RbacService {

    /** The fine-grained permissions used by method-level {@code @PreAuthorize} policies (see {@link Permissions}). */
    private static final List<String> ALL_PERMISSIONS = Permissions.ALL;

    private final PermissionRepository permissions;
    private final RoleRepository roles;

    @Override
    @Transactional
    public void grantAllPermissionsToAdmin() {
        Role admin = roles.findByName("ROLE_ADMIN")
                .orElseThrow(() -> new IllegalStateException("ROLE_ADMIN must exist before granting permissions"));

        ALL_PERMISSIONS.forEach(name -> admin.addPermission(getOrCreatePermission(name)));
        roles.save(admin);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> allPermissions() {
        return ALL_PERMISSIONS;
    }

    private Permission getOrCreatePermission(String name) {
        return permissions.findByName(name).orElseGet(() -> permissions.save(new Permission(name)));
    }
}
