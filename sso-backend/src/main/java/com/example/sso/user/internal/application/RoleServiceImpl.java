package com.example.sso.user.internal.application;

import com.example.sso.shared.IdName;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.internal.domain.AppUser;
import com.example.sso.user.internal.domain.AppUserRepository;
import com.example.sso.user.internal.domain.Role;
import com.example.sso.user.RoleRef;
import com.example.sso.user.internal.domain.RoleRepository;
import com.example.sso.user.RoleService;
import com.example.sso.user.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link RoleService}: roles and role membership (RBAC groups) for admin and SCIM. Returns the
 * {@link RoleRef}/{@link UserAccount} projections; the user-role link mutation lives here so callers
 * never touch the entities.
 */
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roles;
    private final AppUserRepository users;

    @Override
    @Transactional(readOnly = true)
    public Optional<RoleRef> findByName(String name) {
        return roles.findByName(name).map(r -> r);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoleRef> findById(UUID id) {
        return roles.findById(id).map(r -> r);
    }

    @Override
    @Transactional
    public RoleRef getOrCreate(String name) {
        return roles.findByName(name).orElseGet(() -> roles.save(new Role(name)));
    }

    @Override
    @Transactional
    public RoleRef create(String name) {
        return roles.save(new Role(name));
    }

    @Override
    @Transactional
    public void delete(UUID roleId) {
        roles.findById(roleId).ifPresent(roles::delete);
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return roles.count();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleRef> findAll() {
        return roles.findAll().stream().map(RoleRef.class::cast).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdName> idNames(Collection<UUID> ids) {
        return roles.findIdNames(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleRef> page(long startIndex, int count) {
        if (count <= 0) {
            return List.of();
        }
        long zeroBased = Math.max(startIndex - 1, 0);
        int pageNumber = (int) (zeroBased / count);
        return roles.findAll(PageRequest.of(pageNumber, count)).stream().map(RoleRef.class::cast).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserAccount> members(UUID roleId) {
        return users.findByRoles_Id(roleId).stream().map(UserAccount.class::cast).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<UserAccount>> membersByRoleIds(Set<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<UserAccount>> byRole = new HashMap<>();
        for (Object[] row : users.findMembersByRoleIdIn(roleIds)) {
            byRole.computeIfAbsent((UUID) row[0], k -> new ArrayList<>()).add((AppUser) row[1]);
        }
        return byRole;
    }

    @Override
    @Transactional
    public void setMembers(UUID roleId, Set<UUID> userIds) {
        Role role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role not found"));
        List<AppUser> current = users.findByRoles_Id(roleId);
        Set<UUID> currentIds = current.stream().map(AppUser::getId).collect(Collectors.toSet());
        // Managed entities inside this @Transactional method — dirty checking flushes; no explicit saves.
        current.stream().filter(u -> !userIds.contains(u.getId())).forEach(u -> u.removeRole(role));
        Set<UUID> toAdd = userIds.stream().filter(id -> !currentIds.contains(id)).collect(Collectors.toSet());
        users.findAllById(toAdd).forEach(u -> u.addRole(role));
    }
}
