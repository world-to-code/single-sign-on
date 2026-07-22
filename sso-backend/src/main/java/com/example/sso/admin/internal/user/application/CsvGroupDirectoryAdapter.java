package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.metadata.CsvGroupDirectory;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.group.UserGroupService;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * {@link CsvGroupDirectory} over the group service and the admin access policy.
 *
 * <p>Lives here because both halves of the answer do. Existence alone was what the preview asked, and reach
 * was checked only when the import was applied — so a delegate could upload guessed names and read which rows
 * came back importable to learn which groups exist outside their subtree. Asking both questions in one place
 * is what makes the preview's answer the same as the apply's.
 */
@Component
@RequiredArgsConstructor
class CsvGroupDirectoryAdapter implements CsvGroupDirectory {

    private static final String MEMO_KEY = CsvGroupDirectoryAdapter.class.getName() + ".usable";

    private final UserGroupService groups;
    private final AdminAccessPolicy accessPolicy;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public List<String> unusable(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        // Resolved ONCE for the whole file. This sat inside the filter, so a file naming D groups cost D
        // directory reads and up to D squared authorization lookups on a single admin request.
        Map<String, UUID> usable = usableIds(names);
        return names.stream().distinct().filter(name -> !usable.containsKey(name)).toList();
    }

    /**
     * Name to id, for the groups that exist in the acting organization AND the actor may put a member in.
     *
     * <p>Answered from a per-request memo of names already decided, so a name costs one resolution per
     * request no matter how many rows mention it. The apply path asks this once PER ROW — five hundred rows
     * naming two groups each was five hundred directory reads plus a thousand authorization decisions, every
     * one of them re-deriving the actor from the database, all inside that row's own transaction.
     *
     * <p>What that trades: the per-row call used to be an independent re-resolution, guarding the window
     * between planning a file and applying it. That window is one HTTP request wide — the plan is derived
     * inside {@code apply}, not taken from the client — and rows already created before a mid-import
     * revocation would stand regardless, so re-querying never really closed it. The write itself is still
     * checked: {@code addMember} re-validates same-org membership at the point it inserts.
     */
    Map<String, UUID> usableIds(Collection<String> names) {
        Map<String, Optional<UUID>> decided = decidedThisRequest();
        List<String> undecided = names.stream().distinct().filter(name -> !decided.containsKey(name)).toList();
        if (!undecided.isEmpty()) {
            Map<String, UUID> resolved = resolve(undecided);
            undecided.forEach(name -> decided.put(name, Optional.ofNullable(resolved.get(name))));
        }
        Map<String, UUID> usable = new LinkedHashMap<>();
        for (String name : names) {
            decided.get(name).ifPresent(id -> usable.put(name, id));
        }
        return usable;
    }

    /**
     * The names decided so far in this request, by acting organization.
     *
     * <p>Keyed by org because a request may cross tenants, and a verdict from one says nothing about another.
     * Outside a request — a test, background work — there is nowhere to memoize, so a fresh map means every
     * call resolves, which is the old behaviour.
     *
     * <p>Deliberately request-scoped-stale, like the resource scope memo: a group that becomes usable
     * mid-request is seen on the next one. The hazard to avoid is the reverse — never NARROW reach and then
     * re-check it in the same request, because the memo would answer with the wider set and fail open.
     */
    private Map<String, Optional<UUID>> decidedThisRequest() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return new LinkedHashMap<>();
        }
        String key = MEMO_KEY + ":" + orgContext.currentOrg().map(UUID::toString).orElse("platform");
        @SuppressWarnings("unchecked")
        Map<String, Optional<UUID>> decided =
                (Map<String, Optional<UUID>>) request.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
        if (decided == null) {
            decided = new LinkedHashMap<>();
            request.setAttribute(key, decided, RequestAttributes.SCOPE_REQUEST);
        }
        return decided;
    }

    private Map<String, UUID> resolve(Collection<String> names) {
        Map<String, UUID> inReach = orgContext.currentOrg()
                .map(org -> groups.groupIdsByName(names, org))
                .orElseGet(Map::of)
                .entrySet().stream()
                .filter(entry -> accessPolicy.canAccessGroup(entry.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // Reach is not enough: membership CONFERS the group's roles, so the grant has to clear the same
        // ceiling a direct role grant does. One query for the whole set, not one per group.
        Map<UUID, Set<String>> delegated = groups.delegatedRoleNames(inReach.values());
        return inReach.entrySet().stream()
                .filter(entry -> mayConfer(delegated.get(entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * A group delegating no role confers nothing, so there is no ceiling for it to clear — absent from the
     * map, which is how {@code delegatedRoleNames} reports that rather than with an empty set.
     */
    private boolean mayConfer(Set<String> delegatedRoles) {
        return delegatedRoles == null || accessPolicy.mayAssignRoles(delegatedRoles);
    }
}
