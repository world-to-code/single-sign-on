package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.CsvImportPreview;
import com.example.sso.metadata.CsvImportResult;
import com.example.sso.metadata.CsvPlannedUser;
import com.example.sso.metadata.CsvRowFailure;
import com.example.sso.metadata.CsvUserCreator;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.ApiException;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Applying an import, which is the only step here that creates anything.
 *
 * <p>The file is re-read and re-planned rather than taking the preview back from the client. A preview handed
 * back is client input, and this decides which accounts exist — so what the administrator confirmed is a
 * number they saw, not an instruction we execute.
 */
@ExtendWith(MockitoExtension.class)
class CsvImportApplyTest {

    private static final UUID PROFILE = UUID.randomUUID();

    @Mock private ProfileService profiles;
    @Mock private CsvUploadValidator uploads;
    @Mock private CsvImportPlanner planner;
    @Mock private CsvUserCreator creator;

    private CsvImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CsvImportServiceImpl(profiles, uploads, planner, creator);
        lenient().when(profiles.findById(PROFILE)).thenReturn(Optional.of(
                new Profile(PROFILE, "acme", ProfileKind.TENANT, null, true, true)));
        lenient().when(uploads.validateOnly(any(), any())).thenReturn(new CsvUpload("users.csv", "csv"));
    }

    private MultipartRequest request() {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(new MockMultipartFile("file", "users.csv", "text/csv",
                "username\nada\n".getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    private CsvPlannedUser planned(String username, String... groups) {
        return new CsvPlannedUser(username, Map.of("username", username), List.of(groups));
    }

    private void plans(List<CsvPlannedUser> toCreate, List<String> existing, List<CsvRowFailure> failures) {
        when(planner.plan(eq(PROFILE), any())).thenReturn(
                new CsvImportPreview(toCreate.size() + existing.size() + failures.size(),
                        toCreate, existing, failures));
    }

    @Test
    void everyPlannedUserIsCreatedOnTheChosenProfile() {
        plans(List.of(planned("ada"), planned("grace")), List.of(), List.of());

        CsvImportResult result = service.apply(PROFILE, request());

        assertThat(result.created()).isEqualTo(2);
        verify(creator).create(planned("ada"), PROFILE);
        verify(creator).create(planned("grace"), PROFILE);
    }

    /** The plan already decided this; applying must not create them a second time. */
    @Test
    void anExistingAccountIsLeftAlone() {
        plans(List.of(planned("grace")), List.of("ada"), List.of());

        CsvImportResult result = service.apply(PROFILE, request());

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.existing()).containsExactly("ada");
        verify(creator, never()).create(eq(planned("ada")), any());
    }

    /** Rows the plan already refused are carried into the result, not silently dropped. */
    @Test
    void rowsThePlanRefusedStayRefused() {
        CsvRowFailure refused = new CsvRowFailure(4, "metadata.csv.row.duplicateUsername", "ada");
        plans(List.of(planned("grace")), List.of(), List.of(refused));

        assertThat(service.apply(PROFILE, request()).failures()).containsExactly(refused);
    }

    /**
     * The window between confirming and applying is real: somebody else can create that account, or a group can
     * be renamed. One row losing that race must not take the rest of the file with it — which is also why the
     * creator owns its own transaction, since a constraint violation poisons the one it runs in and catching
     * that does not un-poison it.
     */
    @Test
    void aRowThatFailsWhileApplyingIsReportedAndTheRestStillLand() {
        plans(List.of(planned("ada"), planned("grace")), List.of(), List.of());
        doThrow(ConflictException.of("user.username.taken"))
                .when(creator).create(planned("ada"), PROFILE);

        CsvImportResult result = service.apply(PROFILE, request());

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failures()).singleElement()
                .extracting(CsvRowFailure::reason).isEqualTo("user.username.taken");
        verify(creator).create(planned("grace"), PROFILE);
    }

    /** A file whose shape the planner refuses creates nothing at all. */
    @Test
    void aRefusedFileCreatesNothing() {
        when(planner.plan(eq(PROFILE), any()))
                .thenThrow(BadRequestException.of("metadata.csv.unknownColumn", "salary"));

        assertThatThrownBy(() -> service.apply(PROFILE, request()))
                .asInstanceOf(type(ApiException.class))
                .extracting(ApiException::getMessageKey).isEqualTo("metadata.csv.unknownColumn");

        verify(creator, never()).create(any(), any());
    }

    /** A source profile cannot create users, and the file is not even read before that is settled. */
    @Test
    void aSourceProfileCannotBeImportedInto() {
        when(profiles.findById(PROFILE)).thenReturn(Optional.of(
                new Profile(PROFILE, "SCIM", ProfileKind.SCIM, null, false, false)));

        assertThatThrownBy(() -> service.apply(PROFILE, request()))
                .asInstanceOf(type(ApiException.class))
                .extracting(ApiException::getMessageKey).isEqualTo("metadata.profile.notCreatable");

        verify(uploads, never()).validateOnly(any(), any());
        verify(creator, never()).create(any(), any());
    }

    /** Groups travel with the user, so the port receives them and decides whether the actor may grant them. */
    @Test
    void theGroupsTheRowNamedTravelWithTheUser() {
        plans(List.of(planned("ada", "platform", "oncall")), List.of(), List.of());

        service.apply(PROFILE, request());

        verify(creator).create(planned("ada", "platform", "oncall"), PROFILE);
    }
}
