package com.example.sso.metadata.internal.application;

import java.util.Collection;
import java.util.List;

/**
 * Which of these group names the acting organization does not have.
 *
 * <p>Phrased as the negative because that is the answer the import acts on: a group named in a file is never
 * created, so the only thing worth knowing is which names would have to be. A typo that minted a group would
 * be minting a permission boundary nobody decided to grant.
 */
interface CsvGroups {

    /** The names with no group behind them, in the order given. */
    List<String> missing(Collection<String> names);
}
