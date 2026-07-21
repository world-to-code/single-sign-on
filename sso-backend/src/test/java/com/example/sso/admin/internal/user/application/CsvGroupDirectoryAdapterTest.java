package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.group.UserGroupService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Which group names an import may actually use — existence AND reach, answered together.
 *
 * <p>They used to be answered on different paths: the preview asked only whether a group exists, and reach was
 * checked when the import was applied. So a subtree-scoped delegate could upload guessed names and read back
 * which rows came out importable, which told them which groups exist outside their subtree. The property this
 * class exists to hold is that the two answers are one.
 */
@ExtendWith(MockitoExtension.class)
class CsvGroupDirectoryAdapterTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID REACHABLE = UUID.randomUUID();
    private static final UUID OUT_OF_SCOPE = UUID.randomUUID();

    @Mock private UserGroupService groups;
    @Mock private AdminAccessPolicy accessPolicy;
    @Mock private OrgContext orgContext;

    private CsvGroupDirectoryAdapter directory;

    @BeforeEach
    void setUp() {
        directory = new CsvGroupDirectoryAdapter(groups, accessPolicy, orgContext);
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        Map<String, UUID> contents = Map.of("platform", REACHABLE, "finance", OUT_OF_SCOPE);
        lenient().when(groups.groupIdsByName(any(), eq(ORG))).thenAnswer(call -> {
            Collection<String> asked = call.getArgument(0);
            return contents.entrySet().stream().filter(entry -> asked.contains(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        });
    }

    @Test
    void aGroupThatExistsAndTheActorMayUseIsUsable() {
        when(accessPolicy.canAccessGroup(REACHABLE)).thenReturn(true);

        assertThat(directory.unusable(List.of("platform"))).isEmpty();
    }

    @Test
    void aNameNoGroupStandsBehindIsUnusable() {
        assertThat(directory.unusable(List.of("platfrom"))).containsExactly("platfrom");
    }

    /**
     * The finding this class was extracted for: an existing group the actor cannot reach reports exactly as a
     * name that does not exist. Any difference between the two is an existence oracle.
     */
    @Test
    void aGroupOutsideTheActorsReachIsUnusableJustLikeAMissingOne() {
        when(accessPolicy.canAccessGroup(OUT_OF_SCOPE)).thenReturn(false);

        // Both come back as the name itself, so the caller cannot tell which reason applied.
        assertThat(directory.unusable(List.of("finance"))).containsExactly("finance");
        assertThat(directory.unusable(List.of("no-such-group"))).containsExactly("no-such-group");
    }

    @Test
    void reachIsDecidedPerGroupNotPerFile() {
        when(accessPolicy.canAccessGroup(REACHABLE)).thenReturn(true);
        when(accessPolicy.canAccessGroup(OUT_OF_SCOPE)).thenReturn(false);

        assertThat(directory.unusable(List.of("platform", "finance"))).containsExactly("finance");
    }

    /** Fails closed: with no organization bound nothing is usable, so every group-bearing row is refused. */
    @Test
    void nothingIsUsableWithNoOrganizationBound() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());

        assertThat(directory.unusable(List.of("platform"))).containsExactly("platform");
    }

    @Test
    void askingAboutNothingCostsNoQuery() {
        assertThat(directory.unusable(List.of())).isEmpty();
    }
}
