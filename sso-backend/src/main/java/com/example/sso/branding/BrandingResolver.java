package com.example.sso.branding;

import java.util.UUID;

/**
 * What a tenant's deployment is CALLED, for surfaces outside this module that name it — an SMS body, an email
 * subject. Resolved own row, else the platform override, else the built-in default.
 *
 * <p>Deliberately one method returning a String. It used to expose the whole resolved render model, which
 * published this module's marks, theme and per-screen wording types to every other module in order to hand the
 * one consumer a product name. The screens that render the rest of it are inside this module and reach the
 * service directly.
 *
 * <p>It also takes no RLS precondition. The previous signature required the caller to already be inside the
 * target org's context, in Javadoc alone — and a caller that got that wrong did not fail: row-level security
 * filtered its reads away, resolution fell back to the platform default, and that answer was cached under the
 * other tenant's key. The binding now happens inside the implementation, so the contract cannot be got wrong.
 */
public interface BrandingResolver {

    /**
     * The product name to show for {@code orgId}, or for the deployment itself when {@code orgId} is null.
     *
     * <p>NEVER null, never blank, and it does not throw. Every resolution bottoms out in the built-in default,
     * and a read that fails degrades to that same default rather than to an exception — the one caller is
     * naming the sender of a one-time code, and a branding lookup is not a reason to withhold it. Callers
     * therefore need neither a fallback nor a try/catch, which is what stops each of them inventing its own.
     */
    String productName(UUID orgId);
}
