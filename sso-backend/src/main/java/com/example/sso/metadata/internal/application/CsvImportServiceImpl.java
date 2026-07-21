package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.CsvImportPreview;
import com.example.sso.metadata.CsvImportService;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
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
}
