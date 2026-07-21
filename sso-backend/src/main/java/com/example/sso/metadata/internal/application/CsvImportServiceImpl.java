package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.CsvImportPreview;
import com.example.sso.metadata.CsvImportResult;
import com.example.sso.metadata.CsvImportService;
import com.example.sso.metadata.CsvPlannedUser;
import com.example.sso.metadata.CsvRowFailure;
import com.example.sso.metadata.CsvUserCreator;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.ApiException;
import com.example.sso.shared.error.BadRequestException;
import org.springframework.dao.DataIntegrityViolationException;
import com.example.sso.shared.error.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartRequest;

/** Default {@link CsvImportService}: validate the upload, then work out what it would do. */
@Service
@RequiredArgsConstructor
class CsvImportServiceImpl implements CsvImportService {

    /** The form field the console posts the file under. */
    static final String FILE_PART = "file";

    private final ProfileService profiles;
    private final CsvUploadValidator uploads;
    private final CsvImportPlanner planner;
    private final CsvUserCreator creator;
    private final CsvFailureText text;

    @Override
    @Transactional(readOnly = true)
    public CsvImportPreview preview(UUID profileId, MultipartRequest request) {
        // The profile is resolved BEFORE the file is read: an id that is not the caller's, or is a source
        // profile, should cost nothing and reveal nothing, and there is no reason to decode bytes for it.
        Profile profile = profiles.findById(profileId)
                .orElseThrow(() -> NotFoundException.of("metadata.profile.notFound"));
        if (!profile.governsUsers()) {
            throw BadRequestException.of("metadata.profile.notCreatable");
        }
        return planner.plan(profile.id(), uploads.validateOnly(request, FILE_PART).text());
    }

    @Override
    public CsvImportResult apply(UUID profileId, MultipartRequest request) {
        // Deliberately NOT transactional. Each creation runs in its own, so a row that violates a constraint
        // rolls back only itself — catching that violation here would not un-poison a shared transaction, and
        // the partial-failure report would then be describing a rollback that had already taken the lot.
        CsvImportPreview plan = preview(profileId, request);

        int created = 0;
        List<CsvRowFailure> failures = new ArrayList<>(plan.failures());
        for (CsvPlannedUser user : plan.toCreate()) {
            try {
                creator.create(user, profileId);
                created++;
            } catch (DataIntegrityViolationException raceLost) {
                // The preview-to-apply window made real: another import or administrator inserted this username
                // first, so the per-org unique index refused ours. That arrives as a constraint violation, not
                // an ApiException, and it used to escape the loop and 500 the request — losing the
                // partial-failure report this whole design exists to produce. REQUIRES_NEW on the creator is
                // what makes catching it safe: the poisoned transaction was that row's alone.
                failures.add(text.at(user.line(), "user.username.duplicate", user.username()));
            } catch (ApiException refused) {
                // The window between confirming and applying is real: someone else can create that account, or
                // a group can be renamed. Report the row and keep going.
                failures.add(text.at(user.line(), refused.getMessageKey(), refused.getMessageArgs()));
            }
        }
        return new CsvImportResult(created, plan.existing(), failures);
    }
}
