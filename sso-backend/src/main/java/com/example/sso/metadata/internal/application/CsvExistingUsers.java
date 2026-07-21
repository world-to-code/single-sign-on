package com.example.sso.metadata.internal.application;

import java.util.Collection;
import java.util.List;

/**
 * Which of these usernames the acting organization already has.
 *
 * <p>Narrow on purpose. The import needs one question answered about accounts and {@code UserService} answers
 * thirty; depending on the whole of it would let a later change reach for a write from inside a preview, which
 * is the one thing a preview must not do.
 */
interface CsvExistingUsers {

    /** The subset that already exists, in the order given. One query, not one per row. */
    List<String> present(Collection<String> usernames);
}
