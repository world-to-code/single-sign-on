package com.example.sso.admin.internal.user.application;

import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.OwnershipChallenge;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything needed to create one account, assembled at the boundary that knows WHY it is being created.
 *
 * <p>It replaces three overloads of the same method whose last two parameters were a nullable {@code UUID} and
 * an enum sitting next to each other — a positional swap no compiler would have caught, on the call that
 * decides which accounts exist. The two factories say which caller is which, so "an import suppresses the
 * ownership mail" and "an import names its own profile" are facts of the type rather than arguments a caller
 * has to remember to pass.
 *
 * @param profileId the profile to bind the account to, or null to use the organization's default
 */
public record NewUserCommand(NewUser user, Map<String, List<String>> attributeValues, UUID profileId,
                      OwnershipChallenge challenge) {

    /**
     * One account, typed into the console. The organization's default profile governs it, and the new address
     * gets an ownership challenge now — an administrator asserted it, its owner did not.
     */
    public static NewUserCommand fromConsole(NewUser user, Map<String, List<String>> attributeValues) {
        return new NewUserCommand(user, attributeValues, null, OwnershipChallenge.SEND);
    }

    /**
     * A row of an uploaded file. The profile is the one the administrator picked and downloaded a template
     * for, so the default would discard every column they were told to provide; the mail is withheld because
     * one file would otherwise send thousands of them to third-party addresses in a single request.
     */
    public static NewUserCommand fromImport(NewUser user, Map<String, List<String>> attributeValues, UUID profileId) {
        return new NewUserCommand(user, attributeValues, profileId, OwnershipChallenge.SUPPRESS);
    }
}
