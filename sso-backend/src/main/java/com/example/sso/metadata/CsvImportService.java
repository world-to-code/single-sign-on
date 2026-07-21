package com.example.sso.metadata;

import java.util.UUID;
import org.springframework.web.multipart.MultipartRequest;

/**
 * Reading an uploaded CSV as a set of users to create on a profile.
 *
 * <p>Takes the whole multipart request rather than one file, because how MANY files arrived is part of what
 * has to be checked: Spring binds the part it was asked for and ignores the rest, so a request carrying ten
 * files would otherwise be accepted as though it carried one.
 */
public interface CsvImportService {

    /**
     * What the file would do, having done none of it.
     *
     * <p>Separate from applying it on purpose. Every other import path in this system fills existing accounts;
     * CSV is the intended exception that CREATES them, so a file aimed wrongly can populate a tenant with
     * accounts that should not exist. The administrator confirms a count first.
     */
    CsvImportPreview preview(UUID profileId, MultipartRequest request);

    /**
     * Applies the file.
     *
     * <p>Takes the FILE again rather than a confirmed preview. A preview the client hands back is client input,
     * and this one decides which accounts exist — so the file is re-read and re-planned here, and what the
     * caller confirmed is a number they saw, not an instruction we execute.
     *
     * <p>Rows are applied independently: one that fails is reported and the rest still land, because a
     * five-thousand-line file failing whole on its last row helps nobody.
     */
    CsvImportResult apply(UUID profileId, MultipartRequest request);
}
