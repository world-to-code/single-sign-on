package com.example.sso.metadata.internal.application;

import com.example.sso.user.account.BaseUserFields;
import java.util.Set;

/**
 * The vocabulary the template and the importer must agree on.
 *
 * <p>Both sides of the feature read these: the template writes the column and its instruction, the parser
 * looks for it and splits it. They lived on the template service, so the importer reached into another
 * service's implementation for them — and the separator was written twice, once as a regex in the parser and
 * once as prose in the guidance row, which is two places to change and one to forget.
 */
final class CsvColumns {

    /** Optional, and last so it does not crowd the identity columns. */
    static final String GROUPS = "groups";

    /** Several groups in one cell. Stated once, so the template cannot promise a separator the parser ignores. */
    static final String GROUP_SEPARATOR = ";";

    /**
     * The base attributes an import may actually WRITE.
     *
     * <p>A profile synthesises five, and creation carries three. The other two were reaching the file anyway:
     * the template advertised them, the reader accepted them, a row carrying them was reported as importable —
     * and then they were dropped, because there is nowhere on {@code NewUser} for them to go. An administrator
     * who filled the external-id column believed they had seeded the directory join key.
     *
     * <p>They stay out rather than being carried through, and that is deliberate for {@code externalId} in
     * particular: it is an identity-binding key, the thing that decides which directory account an assertion
     * IS. Letting a file set it in bulk would let one upload claim an existing directory identity, which
     * belongs behind its own decision rather than arriving as a column. A phone number could be carried, but
     * it starts unverified and the SMS factor refuses it either way, so it buys nothing today.
     *
     * <p>Stated once, because the template and the reader must agree: whichever forgot would either promise a
     * column nothing writes or refuse one the template printed.
     */
    static final Set<String> WRITABLE_BASE_KEYS =
            Set.of(BaseUserFields.USERNAME, BaseUserFields.EMAIL, BaseUserFields.DISPLAY_NAME);

    private CsvColumns() {
    }
}
