import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { getBranding, type Branding } from "@/branding";
import { applyAccent } from "@/lib/prefs";

/** Unbranded: the built-in mark, the product name, and the default accent. What every surface starts from. */
const DEFAULT: Branding = { logoUrl: null, accentColor: null, productName: null };

const BrandingContext = createContext<Branding>(DEFAULT);

/**
 * Separate from the value so that consumers of the branding do not re-render when only this function's
 * identity changes — and so {@link useBranding} keeps its shape, since almost every caller wants only the mark.
 */
const BrandingRefreshContext = createContext<() => Promise<void>>(async () => {});

/**
 * The host tenant's branding, for the WHOLE app.
 *
 * <p>It used to be fetched inside the auth layout, which meant it reached the sign-in screens and nothing
 * else: the console, the user portal and the loading splash all rendered the built-in shield and the word
 * "Svalinn" no matter what the tenant had configured, and the accent colour reverted to the default the
 * moment a session existed. A tenant that has set their logo has said what this deployment is called, and
 * that answer does not stop being true after sign-in.
 *
 * <p>The endpoint is PUBLIC and resolves the tenant from the request host, so fetching it at the root is
 * safe before anyone has signed in — which is also why it can be fetched exactly once for every surface.
 */
export function BrandingProvider({ children }: { children: ReactNode }) {
  const [branding, setBranding] = useState<Branding>(DEFAULT);

  // A tenant that has not configured branding and a request that fails outright mean the same thing: use the
  // defaults. There is no version of this worth showing anyone an error about.
  const load = useCallback(async (): Promise<void> => {
    const resolved = await getBranding().catch(() => DEFAULT);
    setBranding(resolved);
    applyAccent(resolved.accentColor); // overrides --primary for every screen, not just the auth ones
  }, []);

  // Once, for the page. The provider is mounted at the root, so every surface below it shares this one
  // fetch — which is the whole reason it moved here from the auth layout, where it was re-fetched as the
  // sign-in steps mounted and unmounted.
  useEffect(() => {
    let cancelled = false;
    load().then(() => { if (cancelled) return; });
    return () => { cancelled = true; };
  }, [load]);

  return (
    <BrandingRefreshContext.Provider value={load}>
      <BrandingContext.Provider value={branding}>{children}</BrandingContext.Provider>
    </BrandingRefreshContext.Provider>
  );
}

/** The resolved branding. Defaults until the fetch lands, so a consumer never has to handle "not yet". */
export function useBranding(): Branding {
  return useContext(BrandingContext);
}

/**
 * Re-reads the branding for the whole app. The editor calls this after saving, because the provider sits at
 * the ROOT and never unmounts — so without it a tenant saw their new logo and product name only after a full
 * browser reload, and until then the console kept showing what the branding used to be.
 */
export function useBrandingRefresh(): () => Promise<void> {
  return useContext(BrandingRefreshContext);
}
