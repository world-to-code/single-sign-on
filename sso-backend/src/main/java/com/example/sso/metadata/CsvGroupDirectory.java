package com.example.sso.metadata;

import java.util.Collection;
import java.util.List;

/**
 * Which group names an import may actually put someone in.
 *
 * <p>A port, and implemented where the admin access policy lives, because "does this group exist" is only half
 * the question. The other half — may the acting administrator put a member in it — was asked on the apply path
 * and not on the preview path, so a delegate could learn which group names exist outside their subtree by
 * uploading guesses and reading which rows came back importable. One answer for both paths, so the two cannot
 * disagree and neither distinguishes "not yours" from "not there".
 */
public interface CsvGroupDirectory {

    /**
     * The names with no group the caller may use behind them, in the order given.
     *
     * <p>Phrased as the negative because that is what the import acts on: a group named in a file is never
     * created, so the only thing worth knowing is which names would have to be.
     */
    List<String> unusable(Collection<String> names);
}
