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
}
