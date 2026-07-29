import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { getBranding, type Branding } from "@/branding";
import { applyAccent } from "@/lib/prefs";

/** Unbranded: the built-in mark, the product name, and the default accent. What every surface starts from. */
const DEFAULT: Branding = { logoUrl: null, accentColor: null, productName: null };

const BrandingContext = createContext<Branding>(DEFAULT);

/**
 * The host tenant's branding, for the WHOLE app.
 *
 * <p>It used to be fetched inside the auth layout, which meant it reached the sign-in screens and nothing
 * else: the console, the user portal and the loading splash all rendered the built-in shield and the word
 * "Mini SSO" no matter what the tenant had configured, and the accent colour reverted to the default the
 * moment a session existed. A tenant that has set their logo has said what this deployment is called, and
 * that answer does not stop being true after sign-in.
 *
 * <p>The endpoint is PUBLIC and resolves the tenant from the request host, so fetching it at the root is
 * safe before anyone has signed in — which is also why it can be fetched exactly once for every surface.
 */
export function BrandingProvider({ children }: { children: ReactNode }) {
  const [branding, setBranding] = useState<Branding>(DEFAULT);

  // Once, for the page. The provider is mounted at the root, so every surface below it shares this one
  // fetch — which is the whole reason it moved here from the auth layout, where it was re-fetched as the
  // sign-in steps mounted and unmounted.
  useEffect(() => {
    let cancelled = false;
    // A tenant that has not configured branding and a request that fails outright mean the same thing: use
    // the defaults. There is no version of this worth showing anyone an error about.
    getBranding().catch(() => DEFAULT).then((resolved) => {
      if (cancelled) return;
      setBranding(resolved);
      applyAccent(resolved.accentColor); // overrides --primary for every screen, not just the auth ones
    });
    return () => { cancelled = true; };
  }, []);

  return <BrandingContext.Provider value={branding}>{children}</BrandingContext.Provider>;
}

/** The resolved branding. Defaults until the fetch lands, so a consumer never has to handle "not yet". */
export function useBranding(): Branding {
  return useContext(BrandingContext);
}
