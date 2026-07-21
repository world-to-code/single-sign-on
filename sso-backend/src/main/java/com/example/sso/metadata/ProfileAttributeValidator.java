package com.example.sso.metadata;

import com.example.sso.shared.error.BadRequestException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Checks a set of attribute values against what a profile declares, before anything is written.
 *
 * <p>A profile says which attributes a person has, whether each is required, what type it holds and — for an
 * enum — which values are allowed. Without this the declarations are decoration: the console would render a
 * required field and the server would accept a create that omitted it.
 */
public interface ProfileAttributeValidator {

    /**
     * @param profileId  the profile the values are being written against
     * @param values     attribute key to its values; a key the profile does not declare is rejected rather
     *                   than silently stored, so a typo does not become invisible data
     * @throws BadRequestException on a missing required attribute, a value that
     *                   does not fit its declared type, an undeclared key, or several values for a
     *                   single-valued attribute
     */
    void validate(UUID profileId, Map<String, ? extends Collection<String>> values);

    /** The profile a manually created user is given: the tenant's designated default, else its own. */
    UUID defaultForCreation();
}
