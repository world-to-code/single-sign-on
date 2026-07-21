package com.example.sso.metadata;

import java.util.UUID;

/**
 * Builds the CSV an administrator fills in to create users on a given profile.
 *
 * <p>The template is generated FROM the profile, which is the point: the columns are that profile's
 * attributes, so there is no mapping step and no second schema to drift. An administrator who opens it can
 * see which fields exist, which are required and what each accepts, without reading documentation.
 */
public interface CsvTemplateService {

    /** The template for {@code profileId} — filename and content together, so the two always agree. */
    CsvTemplate templateFor(UUID profileId);
}
